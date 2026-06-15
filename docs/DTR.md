# NomadSync — Decision Track Record

> This document records all significant technical and architectural decisions made during the project, with rationale and evaluated alternatives. Updated as the project evolves.

---

## DTR-001 — Git-based sync instead of OneDrive

**Milestone**: M1
**Status**: Accepted

**Context**: the Obsidian vault needs to stay in sync across multiple Windows machines. OneDrive is incompatible with Obsidian due to asynchronous and concurrent file modifications.

**Decision**: Git + GitHub.

**Rationale**:
- pull/push operations are atomic and synchronous
- native diff enables differential autosave
- no external process touches the files while Obsidian is open

**Evaluated alternatives**:
- OneDrive direct sync: causes conflicts due to concurrent writes
- Syncthing: P2P, requires at least one device to always be on
- Obsidian Sync: official solution but paid (~$4/month)

---

## DTR-002 — `theirs` conflict resolution strategy on pull

**Milestone**: M1
**Status**: Superseded by DTR-013 (M5)

**Context**: the pull runs automatically at logon without user supervision. In case of a Git conflict, the process would block waiting for manual intervention.

**Decision**: `git pull -X theirs` — on conflict, the remote version always wins.

**Rationale**: the remote repository represents the last version consciously saved via logoff. It is the source of truth. Every overwrite is tracked in the log.

**Accepted risks**: uncommitted local changes could be lost in case of a conflict before push. Mitigated by periodic autosave and `git stash` before pull.

---

## DTR-003 — Logon sequence: stash → pull → stash pop

**Milestone**: M1
**Status**: Accepted

**Context**: if uncommitted local changes are present at logon, `git pull` refuses to proceed.

**Decision**: run `git stash` before pull and `git stash pop` after.

**Rationale**: preserves local changes during pull without requiring an explicit commit. Transparent behaviour for the user.

**Evaluated alternatives**:
- `git reset --hard` before pull — rejected, destroys local changes with no recovery option

---

## DTR-004 — Logon/logoff hook via Task Scheduler instead of gpedit.msc

**Milestone**: M1
**Status**: Accepted

**Context**: scripts must run automatically at Windows logon and logoff.

**Decision**: Task Scheduler (`taskschd.msc`).

**Rationale**:
- available on all Windows editions including Home
- inspectable UI, execution history
- XML task export for reproducibility across machines

**Evaluated alternatives**:
- Group Policy Editor (`gpedit.msc`) — rejected, not available on Windows Home, less flexible for periodic timers

---

## DTR-005 — Differential autosave via `git diff --quiet`

**Milestone**: M1
**Status**: Accepted

**Context**: the scheduled autosave must not generate empty commits when no file changes are detected.

**Decision**: use `git diff --quiet` as a guard — exit code 0 means no changes, exit code 1 means changes are present.

**Rationale**: native Git, zero additional dependencies, clear semantics via exit code.

---

## DTR-006 — Fat JAR via `maven-assembly-plugin`

**Milestone**: M1
**Status**: Accepted

**Context**: the JAR must be executable standalone from Task Scheduler and the command line, without requiring external classpaths.

**Decision**: `maven-assembly-plugin` with `jar-with-dependencies` descriptor.

**Rationale**: produces a single self-contained artifact; simplifies deployment across machines — copying the `target/` folder is sufficient.

---

## DTR-007 — Resources copied to `target/` via `maven-resources-plugin`

**Milestone**: M1
**Status**: Accepted

**Context**: `NomadSync.bat` and `config.properties` must be placed alongside the JAR to be resolved via relative paths.

**Decision**: `maven-resources-plugin` copies files from `src/main/resources/` to `target/` during the `package` phase.

**Rationale**: the deployment structure is self-contained in `target/`; no manual post-build configuration needed.

---

## DTR-008 — Separate configuration files per environment

**Milestone**: M1
**Status**: Accepted

**Context**: the properties file contains Git credentials (GitHub token) that must not be committed to version control.

**Decision**: `config.dev.properties` and `config.prod.properties` excluded via `.gitignore`; `config.properties.template` committed as a reference.

**Rationale**: clean separation between configuration and code; credential security; simplified onboarding via the template file.

---

## DTR-009 — Thread-safe logging via `synchronized`

**Milestone**: M1
**Status**: Superseded by DTR-018 (M5) — LogWriter implementations now own thread-safety individually

**Context**: `AutosaveScheduler` runs on a separate thread and could write to the log concurrently with the main thread.

**Decision**: `LogService.log()` declared `synchronized`.

**Rationale**: simple and correct solution for the expected concurrency level (two threads at most). Negligible overhead for file I/O operations.

**Future alternatives**: `ReentrantLock` or `BlockingQueue` with a dedicated writer thread, should concurrency increase.

---

## DTR-010 — `SyncOrchestrator` as intermediate layer between `Main` and `GitService`

**Milestone**: M1
**Status**: Accepted

**Context**: the logic for coordinating operations (e.g. stash before pull, exit code handling) belongs neither to `Main` nor to `GitService`.

**Decision**: introduce `SyncOrchestrator` as a dedicated layer. `Main` calls the orchestrator; `GitService` executes only individual Git commands.

**Rationale**: separation of concerns; `GitService` remains independently testable; business logic is centralised and not duplicated.

---

## DTR-011 — Event-driven architecture for SyncOrchestrator

**Milestone**: M2
**Status**: Accepted

**Context**: `SyncOrchestrator` must coordinate Git operations coming from multiple callers (Task Scheduler logon/logoff, `AutosaveScheduler`, tray icon). Git is serial — concurrent operations on the same repository cause conflicts.

**Decision**: event-driven architecture with a priority queue. Callers publish events; the orchestrator consumes from the queue serially.

**Rationale**: concurrency is managed in a single place; callers are decoupled from the orchestrator; the pattern prepares the mindset for microservices.

**Evaluated alternatives**:
- Direct calls (`orchestrator.pull()`) — rejected, every caller must know the orchestrator, concurrency handling spreads across the codebase

---

## DTR-012 — Event priority scale (original)

**Milestone**: M2
**Status**: Superseded by DTR-013 (M5)

**Context**: events of different types may coexist in the queue simultaneously. An ordering that reflects the relative importance of operations is required.

**Decision**:

| Priority | Event |
|---|---|
| 1 | PULL_LOGON |
| 2 | PUSH_MANUAL |
| 3 | PUSH_LOGOFF |
| 4 | AUTOSAVE |

**Rationale**: pull is a precondition for everything else. Manual push reflects explicit user intent and precedes logoff. Autosave is tolerant and deferrable.

---

## DTR-013 — SYNCHRONIZE replaces PULL_MANUAL and PUSH_MANUAL, new priority scale

**Milestone**: M5
**Status**: Accepted — supersedes DTR-012

**Context**: `PULL_MANUAL` and `PUSH_MANUAL` were separate events. Every manual sync requires both pulling remote changes and pushing local ones. Separate events force the user to think in Git terms and expose them to rejected non-fast-forward errors.

**Decision**: single `SYNCHRONIZE` event replaces both.

| Priority | Event |
|---|---|
| 1 | `PULL_LOGON` |
| 2 | `SYNCHRONIZE` |
| 3 | `PUSH_LOGOFF` |
| 4 | `AUTOSAVE` |

**Rationale**: one event, one mental model. Non-fast-forward errors absorbed internally.

---

## DTR-014 — Deduplication strategy: latest wins

**Milestone**: M2
**Status**: Accepted

**Context**: events of the same type can accumulate in the queue (e.g. autosave every 15 minutes, double-click on the tray icon).

**Decision**: a queued event is replaced by the most recent one of the same type. An event currently being executed is not interrupted — the new event waits in the queue.

**Rationale**: an autosave represents a snapshot of the current moment, not an incremental operation. Queuing two of them adds no value.

---

## DTR-015 — Retry with exponential backoff

**Milestone**: M2
**Status**: Accepted

**Context**: Git operations can fail due to network absence. Unlimited retries saturate the queue; fixed-interval retries hammer an unavailable resource.

**Decision**: exponential backoff with a maximum of 3 attempts. Progressive delay: 30s → 60s → 120s. After the third failure the event is discarded.

**Rationale**: standard pattern in distributed systems. Avoids overloading an unavailable resource. Maximum wait time (~3.5 minutes) acceptable for a logon pull.

**Accepted risks**: if the network is absent for the entire session, the logon pull fails definitively. Mitigated by the notification hook.

---

## DTR-016 — Notification hook as dependency inversion

**Milestone**: M2
**Status**: Accepted — tray implementation delivered in M5

**Context**: priority-1 failures (PULL_LOGON) must be communicated to the user. The tray icon was out of scope for M2.

**Decision**: the orchestrator exposes a `NotificationHook` interface with a default implementation that writes to the log. The tray attaches later by implementing the same interface without modifying the orchestrator.

**Rationale**: dependency inversion — the orchestrator depends on the abstraction, not the implementation. Prepares the architecture for the tray without blocking the current sprint.

---

## DTR-017 — Separation of local commit and remote push

**Milestone**: M2
**Status**: Accepted

**Context**: autosave is a frequent, silent checkpoint. Manual push and logoff are remote synchronisation operations. Treating them the same way would expose autosave to unnecessary network failures.

**Decision**:
- `AUTOSAVE` → local commit only
- `PUSH_MANUAL` / `PUSH_LOGOFF` / `SYNCHRONIZE` → local commit + remote push

**Rationale**: resilience — autosave works without a network connection. Separation of concerns — local commit is always available; remote push is a distinct and fallible operation. Exponential backoff retry applies only to operations that touch the remote.

---

## DTR-018 — `hasUncommittedChanges()` guard before stash/stashPop

**Milestone**: M2
**Status**: Accepted

**Context**: `git stash pop` on an empty stash returns exit code 1 with an error. If the logon pull runs on a clean working tree, the stash is empty and the subsequent `stashPop` would fail, unnecessarily triggering the retry logic.

**Decision**: `gitService.hasUncommittedChanges()` as a mandatory guard before `stash()` and `stashPop()`. `stashPop()` is called only if `stash()` was called.

**Implementation**: `git status --porcelain` — stable, locale-independent output. Empty output = clean working tree. Non-empty output = changes present (staged or unstaged).

**Rationale**: `git diff --quiet` checks only unstaged changes — insufficient. `git status --porcelain` covers all cases including staged but uncommitted changes.

---

## DTR-019 — `notify()` renamed to `onFailure()` in NotificationHook

**Milestone**: M2
**Status**: Accepted

**Context**: the `NotificationHook` interface defined a method `notify(SyncEvent event)`. `Object.notify()` is a native Java method on all objects — the name collision generates ambiguity and compiler warnings.

**Decision**: method renamed to `onFailure(SyncEvent event, String message)` with the addition of a `message` parameter to communicate the actual failure cause.

**Rationale**: semantic clarity — `onFailure` describes the contract exactly. The `message` parameter allows the tray implementation to display a contextual message to the user without inspecting the event.

---

## DTR-020 — Plain Thread instead of ExecutorService for the worker loop

**Milestone**: M2
**Status**: Accepted

**Context**: `SyncOrchestrator` needs a dedicated thread running an infinite loop consuming events from the queue. An earlier draft passed an `ExecutorService` via the constructor.

**Decision**: plain `Thread` instantiated internally by the orchestrator.

**Rationale**: `ExecutorService` is useful for thread pools or tasks with `Future` semantics. A single worker with an infinite loop benefits from none of its abstractions — it added a constructor parameter without providing value. Simplicity is preferred.

**Shutdown**: `worker.interrupt()` unblocks `queue.consume()` (which internally calls `PriorityBlockingQueue.take()`); the loop catches `InterruptedException` and terminates. `worker.join()` in `stop()` ensures the current task finishes before the JVM shuts down.

**Evaluated alternatives**:
- `ExecutorService` with `shutdown()` — rejected, correct but oversized for a single thread with loop semantics

---

## DTR-021 — Shutdown hook registered in Main, not in SyncOrchestrator

**Milestone**: M2
**Status**: Accepted — extended in M6 for multi-vault (DTR-031)

**Context**: the first implementation registered the JVM shutdown hook inside `SyncOrchestrator.start()`. This prevented controlling the shutdown order between the scheduler and the orchestrator.

**Decision**: shutdown hook registered in `Main`, which owns the wiring of all components.

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        scheduler.stop();     // stop publishing first
    orchestrator.stop();  // then drain and stop consuming
}, "nomadsync-shutdown"));
```

**Rationale**: `Main` is the natural place to decide shutdown order — the same place that decides startup order. Stopping the scheduler first prevents publishing events onto a queue that is no longer being consumed.

---

## DTR-022 — Adoption of Git Flow as branching strategy

**Milestone**: M2
**Status**: Accepted

**Context**: the project grows in complexity and milestones follow each other with distinct objectives. Committing directly to `main` or `develop` does not separate work in progress from integrated code.

**Decision**: Git Flow AVH Edition as the standard branching strategy.

| Branch | Role |
|---|---|
| `main` | Released code only. Tagged at every milestone. |
| `develop` | Continuous sprint integration. |
| `feature/*` | One branch per grooming objective. |
| `release/*` | Release preparation — version bump, final fixes. |
| `hotfix/*` | Urgent fixes on `main`, merged into `develop` as well. |

**Rationale**: clean separation between work in progress and stable code; per-feature diffs readable; direct mapping to the adopted Agile methodology — each grooming objective becomes a `feature/*` branch.

**Operational note**: Git Flow AVH Edition is bundled with Git for Windows by default — no separate installation required. Initialised with `git flow init` on an existing repository, without affecting existing branches or history.

**Evaluated alternatives**:
- trunk-based development — rejected, suited to teams with mature CI/CD, premature for a solo learning project

---

## DTR-023 — `CommandUtil` as shared process execution utility

**Milestone**: M3
**Status**: Accepted — extended in M5 with `runCommandToFile` (DTR-026)

**Context**: `GitService` contained two private methods (`runCommand`, `runCommandWithOutput`) for executing shell processes via `ProcessBuilder`. The same logic was needed in test helpers to initialise and manipulate temporary Git repositories without duplicating the implementation.

**Decision**: extract process execution into a standalone `CommandUtil` utility class in the `util` package with static methods and an optional `LogService` parameter.

**Rationale**: eliminates duplication between production code and test helpers; keeps `GitService` focused on Git semantics; `CommandUtil` is reusable for any future process execution need.

**Design**: `runCommand(String dir, List<String> command)` overload without `LogService` for test use where logging is not needed; `runCommand(String dir, List<String> command, LogService)` for production use.

---

## DTR-024 — Package-private test constructors on `SyncEvent` and `AutosaveScheduler`

**Milestone**: M3
**Status**: Accepted

**Context**:
- `SyncEventQueueTest` needed to create events with controlled timestamps to test latest-wins deduplication deterministically. The production constructor sets `timestamp` via `System.currentTimeMillis()` — two events created in the same millisecond are indistinguishable.
- `AutosaveSchedulerTest` cannot wait real minutes for the scheduler to fire. The production constructor accepts `intervalMinutes` as a `long` with implicit `TimeUnit.MINUTES`.

**Decision**:
- `SyncEvent`: package-private constructor `SyncEvent(EventType type, long timestamp)` visible only within the `orchestrator` package.
- `AutosaveScheduler`: package-private constructor `AutosaveScheduler(SyncEventQueue, LogService, long interval, TimeUnit timeUnit)`. The public constructor delegates to it with `TimeUnit.MINUTES`.

**Rationale**: keeps fields semantically immutable from external callers while enabling deterministic test scenarios without `Thread.sleep`. Preferred over public setters which would expose unnecessary mutability in production.

**Trade-off accepted**: `SyncEvent.timestamp` field removed `final` modifier to support the interim `setTimestamp()` used before the test constructor was introduced. Tracked as a known code smell in test code — the test constructor is the correct long-term solution.

---

## DTR-025 — `SyncEventQueue` used as real instance in `SyncOrchestratorTest`

**Milestone**: M3
**Status**: Accepted

**Context**: initial test implementation attempted to mock `SyncEventQueue` via Mockito. Mockito failed to mock it due to ByteBuddy limitations on Java 25.

**Decision**: use a real `SyncEventQueue` instance in `SyncOrchestratorTest`. Mock only `GitService` and `NotificationHook`.

**Rationale**: `SyncEventQueue` is pure logic with no side effects — it is an ideal candidate for a real instance in tests. Mocking it would test the mock, not the behaviour. The correct rule is: mock external dependencies with side effects, use real instances for value objects and pure logic.

---

## DTR-025b — JDK downgrade from 25 to 21

**Milestone**: M3
**Status**: Accepted

**Context**: Mockito 5.12.0 uses ByteBuddy, which did not support Java 25 class files (version 69). All mock creation failed with `IllegalArgumentException`.

**Decision**: downgrade the active JDK to Oracle OpenJDK 21 LTS. Update `pom.xml` to use `maven.compiler.release=21`.

**Rationale**: Java 21 is the current LTS with broad adoption. Using `--release` instead of `source`/`target` also sets the bootstrap classpath automatically, eliminating the related compiler warning.

---

## DTR-025c — Testing conventions: empty-file handling and timer resolution

**Milestone**: M3
**Status**: Accepted

**Context**:
- `LogServiceTest.log_belowMinLevel_doesNotWrite` verifies that a `DEBUG` message is not written when `log.level=INFO`. Since nothing is written, the log file is never created. The test helper threw `RuntimeException` when the file was absent, causing the test to error instead of pass.
- `AutosaveSchedulerTest` used `Thread.sleep(2)` to wait for the scheduler to fire after a 1ms interval. On Windows, the system timer has a resolution of approximately 15ms — `sleep(2)` can effectively sleep 0ms or 15ms depending on thread scheduling.

**Decision**:
- `readLogFile` test helper returns an empty string if the file does not exist, instead of throwing.
- Use `Thread.sleep(50)` as the minimum safe wait in scheduler tests on Windows.

**Rationale**: absence of the file is the expected outcome of the test scenario, not an error condition. A conservative sleep value exceeds the 15ms timer granularity with a safe margin, eliminating non-determinism without making tests noticeably slow.

---

## DTR-026 — SYNCHRONIZE conflict strategy: `-X ours` with FIFO backup and remote-conflicts

**Milestone**: M5
**Status**: Accepted

**Context**: `-X theirs` (DTR-002) overwrites local work — inappropriate during an active session. Strategy verified through direct field experimentation.

**Decision**: `-X ours`. Algorithm:

```
SYNCHRONIZE
│
├─ IF local changes (git status --porcelain != empty)
│   └─ git add -A && git commit -m "sync: local changes before pull"
│
├─ git pull
│   ├─ SUCCESS → git push → done
│   └─ CONFLICT
│       ├─ git merge --abort  (ignore non-zero exit)
│       ├─ FIFO backup → backups/<vault>_{timestamp}/  (max 3)
│       ├─ git pull -X ours --no-edit
│       ├─ for each "Auto-merging" line in stdout
│       │   └─ git --no-pager show FETCH_HEAD:<file>
│       │       → temp file → remote-conflicts/<vault>_{timestamp}/<file>
│       ├─ git push
│       └─ return conflicted file list to caller
```

**Field-verified constraints**:
- `git merge --abort` non-zero if no merge active — ignore correctly
- `-X ours` requires local changes committed before execution
- Use `FETCH_HEAD`, not `MERGE_HEAD` — does not exist post-merge
- `--no-pager` mandatory on `git show` — prevents opening `less`
- `--no-edit` mandatory on `git pull -X ours` — prevents opening commit editor

**Rationale**: `-X ours` preserves local work by design. Backup is a silent safety net. User notified only when divergence is real.

**Implementation note (M5/M6 multi-vault refactor)**: `git show FETCH_HEAD:<file>` may produce binary content. `CommandUtil.runCommandToFile` uses `ProcessBuilder.redirectOutput(File)` to write directly to a temp file — no data passes through the JVM heap. `VaultService.saveConflict` then moves the temp file to its final destination via `Files.move` (atomic on same-filesystem moves). `GitignoreException` during snapshot creation is wrapped as `GitException` so the orchestrator's retry strategy applies uniformly.

---

## DTR-027 — FIFO backup — maximum 3 snapshots per vault

**Milestone**: M5
**Status**: Accepted

**Context**: conflict resolution creates full vault snapshots. Without retention, they accumulate indefinitely.

**Decision**: max 3 per vault. FIFO — oldest deleted before new one when limit reached. Path: `backups/<vault-name>_{timestamp}/`. Format `YYYY-MM-DD_HH-mm` — human-readable, sorts chronologically in Explorer.

**Implementation note (M5/M6 multi-vault refactor)**: implemented in `VaultService.makeVaultSnapshot` via `Files.walkFileTree` + `SimpleFileVisitor`. Active `.gitignore` patterns (read via `GitignoreService.forSnapshot`) exclude matching files and directories from the snapshot. `backupsRoot` and `conflictsRoot` are configurable via `path.backup`/`path.conflicts` properties, falling back to subdirectories of `user.dir`.

---

## DTR-028 — TrayIcon — four visual states

**Milestone**: M5
**Status**: Planned — UI layer deferred, backend wiring (broadcast/routing) ready

**Context**: user needs passive sync status feedback without opening any window.

**Decision**: four states via `trayIcon.setImage()` with pre-loaded images:
- **Idle** — static green
- **Syncing** — animated
- **Error** — red: last sync failed after 3 retries
- **Conflict** — orange: files in `remote-conflicts/` awaiting review

Left-click → `SYNCHRONIZE` on current vault. Right-click → `ContextMenu`. Hover → tooltip "Last sync: X minutes ago".

**Rationale**: consistent with Dropbox / OneDrive / Docker tray conventions.

---

## DTR-029 — ContextMenu — zero cognitive decisions

**Milestone**: M5
**Status**: Planned — UI layer deferred, backend events (SYNCHRONIZE broadcast/targeted, PULL_LOGON) ready

**Context**: users need fast access to sync operations without opening MainWindow and without knowing Git concepts.

**Decision**: `PopupMenu` AWT with four grouped sections:

```
● <current vault>  ▶   → VaultSwitcherPanel
─────────────────────
Sync current vault      → SYNCHRONIZE on current vault
Sync all vaults         → SYNCHRONIZE broadcast
Pull current vault      → PULL_LOGON on current vault
─────────────────────
Last sync: X min ago    (non-clickable label)
View log                → MainWindow tab Log (M6+)
─────────────────────
Open Dashboard          → MainWindow (M6+)
Open vault folder       → Desktop.getDesktop().open(vault.path)
─────────────────────
Exit
```

**Rationale**: every label describes exactly what it does — the user does not need to know what a pull or push is.

---

## DTR-030 — VaultSwitcherPanel and ToastNotification — design specification

**Milestone**: M5
**Status**: Planned — UI layer deferred to post-multi-vault-refactor milestones

**Context**: vault switching must be accessible without opening MainWindow. Sync completes asynchronously — conflict resolution requires an actionable notification with direct folder access.

**Decision**:

**VaultSwitcherPanel** — AWT `Menu` nested as submenu on the first ContextMenu entry. `CheckboxMenuItem` per vault — current vault has a checkmark. "Save on selection" `CheckboxMenuItem` fixed at the bottom, outside scroll area. On selection: update `current-vault.json`, update `TrayIcon` tooltip, and if "Save on selection" active — publish `SYNCHRONIZE` on the selected vault.

**ToastNotification** — three scenarios, two strategies:
- **Success** — AWT native `trayIcon.displayMessage()`, auto-dismiss
- **Conflict resolved** — JavaFX `Dialog<ButtonType>` (or `Alert` with custom content), persistent: "Apri versioni remote" → `Desktop.getDesktop().open(remote-conflicts/<vault>_{timestamp}/)` (most recent snapshot, not root); "Apri backup locale" → `Desktop.getDesktop().open(backups/<vault>_{timestamp}/)`. Shown via `Platform.runLater()` since the publishing thread is the AWT tray thread.
- **Network failure** — JavaFX `Dialog<ButtonType>`, persistent, priority-1 events only — same `Platform.runLater()` requirement

`Desktop.getDesktop().open(File)` is the correct Java API for native folder opening — zero dependencies, works on Windows (Explorer) and macOS (Finder).

**Rationale**: `VaultSwitcherPanel` has the most side effects of any quick-menu component — documenting them explicitly prevents implementation surprises. Notification weight matched to severity.

---

## DTR-031 — Vault identity: `owner` field and derived `repoSlug`

**Milestone**: M5/M6 (multi-vault refactor)
**Status**: Accepted

**Context**: the multi-vault architecture needs a universal identifier per vault for logging, routing, and cross-device consistency — distinct from the local filesystem path. An earlier draft considered storing a `repoSlug` field directly in `vaults.json`, duplicating information derivable from `owner` and `name`.

**Decision**: `Vault` gains an `owner` field (GitHub account owning the remote repository). `getRepoSlug()` is a derived method returning `<owner>/<name>`, e.g. `AleDeP10/public-vault`. Not persisted as a separate field.

**Rationale**: avoids redundant storage and the risk of `repoSlug` and `name` diverging. `repoSlug` is used as the `universalId` in structured log entries (`LogService.withVault(repoSlug)`), enabling log queries scoped to a vault regardless of local path or device.

**Per-vault Git credentials**: `Vault` also carries optional `gitName`, `gitEmail`, `gitUsername`, `gitToken`. `gitUsername` may differ from `owner` — e.g. a contributor (`AleDeP10`) accessing a vault owned by another account (`belmani-apex`). All credential fields are optional; if absent, global Git configuration applies. **Status**: fields defined in the domain model, resolution logic in `GitService` not yet implemented — tracked as open item for a future milestone.

---

## DTR-032 — Domain/DTO separation: no Jackson annotations in domain classes

**Milestone**: M5/M6 (multi-vault refactor)
**Status**: Accepted

**Context**: `Vault` originally carried `@JsonCreator`/`@JsonProperty` annotations directly. Mixing serialisation concerns into domain classes couples the domain model to a specific JSON library and clutters classes with annotations irrelevant to business logic.

**Decision**: all Jackson annotations live exclusively in the `dto` package. `VaultDto`, `VaultRootDto`, `SocketMessageDto` each provide `toDomain()` (DTO → domain) and, where applicable, `fromDomain()` (domain → DTO) as the single naming convention across all DTOs. `JsonMapper` is the only class that interacts with both layers.

**Rationale**: domain classes (`Vault`, `SyncEvent`) remain free of serialisation concerns and fully testable without Jackson on the classpath. `toDomain()`/`fromDomain()` as a uniform naming convention makes the mapping direction explicit and consistent across all DTOs.

---

## DTR-033 — `VaultContext` as a record

**Milestone**: M5/M6 (multi-vault refactor)
**Status**: Accepted

**Context**: `VaultContext` groups a `Vault`, its `SyncEventQueue`, its `SyncOrchestrator`, and a `ScheduledFuture` used for shutdown — four components set once at construction and never replaced.

**Decision**: `VaultContext` is a Java record with four components, no redefined accessors.

**Rationale**: all-immutable, set-once-at-construction data is exactly the use case records were designed for. Eliminates boilerplate getters; composition over inheritance — `Vault` remains a pure value object with no execution concerns.

---

## DTR-034 — `LogService`: multi-writer fan-out and vault scoping

**Milestone**: M5/M6 (multi-vault refactor)
**Status**: Accepted — supersedes DTR-009

**Context**: the original `LogService` wrote synchronously to a single file and console, with `synchronized` for thread safety. Multi-vault requires: (a) multiple simultaneous output targets (console, file, Seq structured logging), and (b) every log line tagged with the originating vault.

**Decision**:
- `LogService` builds a `List<LogWriter>` from the `log.writers` property (comma-separated: `console`, `file`, `seq`). Unknown tokens and missing required properties (`log.path` for `file`, `log.seq.url` for `seq`) are reported to `stderr` and skipped — the service starts regardless.
- Each log entry carries a `repoSlug` (`<owner>/<name>`, or `"SYSTEM"` for the system-level instance).
- `withVault(repoSlug)` returns a new `LogService` instance **sharing the same underlying writers** — no file reopened, no Seq reconnection, no new daemon thread. Only the `repoSlug` written per line differs.
- Thread-safety is delegated to each `LogWriter`: `FileLogWriter` uses `synchronized`; `SeqHttpLogWriter` uses an internal `BlockingQueue` consumed by a daemon thread.

**Rationale**: fan-out to multiple targets and per-vault scoping are orthogonal concerns — writers don't need to know about vaults, and vault-scoping doesn't need to know about writer internals. Sharing writers across `withVault()` instances avoids resource duplication (file handles, HTTP connections, threads) when N vaults log concurrently.

**`InMemoryLogWriter`**: intentionally not configurable via `log.writers` — it is an in-process tool instantiated directly by code that needs to inspect log output at runtime (e.g. future tray UI buffering), not a user-facing output target.

---

## DTR-035 — `SeqHttpLogWriter`: async delivery via internal queue and daemon thread

**Milestone**: M5/M6 (multi-vault refactor)
**Status**: Accepted

**Context**: shipping log events to a remote Seq server via HTTP must not block the calling thread (application threads, orchestrator workers). Network failures or a slow Seq server must not propagate as latency into business logic.

**Decision**: `SeqHttpLogWriter` formats each event as CLEF and offers it to an internal `BlockingQueue` (capacity 1000). A dedicated daemon thread (`seq-log-writer`) consumes the queue and performs the HTTP POST. If the queue is full, the event is dropped with a `stderr` warning — never blocks the caller. `close()` drains the queue (up to 5s) before interrupting the worker if still alive.

**Rationale**: write-side never blocks; bounded queue provides backpressure without unbounded memory growth; daemon thread ensures the JVM is not kept alive if `close()` is skipped on abnormal termination. HTTP failures (non-2xx, `IOException`) are logged to `stderr` and the worker continues — one lost event does not affect subsequent ones.

---

## DTR-036 — `GitignoreService`: stateless service, type-safe pattern casting

**Milestone**: M5/M6 (multi-vault refactor)
**Status**: Accepted

**Context**: `.gitignore` management requires three pattern tiers — SYSTEM (OS artifacts, never negatable), APP (tool-specific, e.g. Obsidian/Logseq plugin caches), USER (free-form). `save()` receives a flat `List<GitignorePattern>` that must be partitioned back into these tiers, with `SystemPattern extends GitignorePattern` — Java generics are not covariant, so `List<GitignorePattern>` cannot be assigned to `List<SystemPattern>`.

**Decision**: `GitignoreService` holds no mutable instance state — every method receives `vaultPath` and operates on parameters/return values only, making it trivially thread-safe across concurrent vaults. The unsafe cast is avoided via a type-safe Stream pipeline:

```java
List<SystemPattern> system = systemRaw.stream()
        .filter(SystemPattern.class::isInstance)
        .map(SystemPattern.class::cast)
        .collect(Collectors.toList());
```

`Collectors.groupingBy(GitignorePattern::getLevel)` partitions by tier; `getOrDefault(level, List.of())` prevents `NullPointerException` when a tier is absent from the input.

**Rationale**: `filter(isInstance).map(cast)` is statically type-safe — no `@SuppressWarnings`, no runtime `ClassCastException` risk, non-matching elements silently excluded rather than crashing. Statelessness means no synchronization needed when multiple vaults call `forSnapshot()` concurrently.

---

## DTR-037 — `CommandUtil.runCommandToFile`: binary-safe OS-level output redirection

**Milestone**: M5/M6 (multi-vault refactor)
**Status**: Accepted — extends DTR-023

**Context**: `git show FETCH_HEAD:<file>` for a conflicted file may return binary content (images, PDFs). Existing `CommandUtil` methods read stdout line-by-line via `BufferedReader`, which corrupts binary data.

**Decision**: `CommandUtil.runCommandToFile(directory, command, outputFile)` uses `ProcessBuilder.redirectOutput(outputFile.toFile())` — the OS writes the process's stdout directly to the target file, bypassing the JVM heap entirely. `stderr` is discarded (`ProcessBuilder.Redirect.DISCARD`); the caller checks the exit code for failures.

**Rationale**: the only reliable way to capture arbitrary binary output from a child process without risking encoding corruption. Used by `GitService.synchronize()` to extract the remote version of conflicted files to a temp file, which `VaultService.saveConflict` then moves to its final destination.

---

## DTR-038 — `VaultService.saveConflict`: atomic move from temp file to conflicts root

**Milestone**: M5/M6 (multi-vault refactor)
**Status**: Accepted

**Context**: after `CommandUtil.runCommandToFile` writes a conflicted file's remote version to a temp path, it needs to land in `conflictsRoot/<conflictDirName>/<filename>`. An earlier approach considered passing `byte[]` content directly, which would load entire (potentially large) files into memory.

**Decision**: `saveConflict(conflictDirName, filename, sourcePath)` takes a `Path` to an already-written temp file and moves it via `Files.move(sourcePath, conflictDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING)`, creating `conflictDir` if needed.

**Rationale**: `Files.move` is atomic on same-filesystem moves — no partial writes visible to concurrent readers. Avoids loading file content into JVM memory regardless of size. The caller wraps the call in `try/finally` with `Files.deleteIfExists(sourcePath)` as a safety net — a no-op in the success path since `move` already removed the source.

---

## DTR-039 — Broadcast queue and dispatcher for cross-vault events

**Milestone**: M6 (multi-vault wiring)
**Status**: Accepted

**Context**: `AUTOSAVE` must reach every registered vault without `AutosaveScheduler` knowing the vault list. `SYNCHRONIZE` may target either a specific vault or all vaults (M5 DTR-029 "Sync all vaults"). An earlier draft passed `List<SyncEventQueue>` directly to `AutosaveScheduler`, coupling the scheduler to vault topology.

**Decision**: `Main` wires a single broadcast `SyncEventQueue`, decoupled from per-vault queues. A dedicated `nomadsync-broadcaster` thread consumes from it and routes:
- `event.getVaultId() == null` → fan-out, publish to **all** per-vault queues
- `event.getVaultId() != null` → publish to the **matching** per-vault queue only (warn + discard if not found)

`AutosaveScheduler` retains its original single-queue constructor signature, now pointed at the broadcast queue — `new SyncEvent(EventType.AUTOSAVE, null)`.

**Rationale**: a single well-known publish target serves both pure-broadcast (`AUTOSAVE`, always `vaultId = null`) and dual-mode (`SYNCHRONIZE`, `vaultId` either `null` or specific) cases with one dispatcher and no duplicated routing logic. `AutosaveScheduler` requires zero changes beyond what queue reference it receives — same pattern usable for future tray-originated broadcast actions.

---

## DTR-040 — Per-vault `SyncOrchestrator` wiring, threaded startup

**Milestone**: M6 (multi-vault wiring)
**Status**: Accepted — extends DTR-021

**Context**: `SyncOrchestrator` constructor reads `vault.path` from `Properties` and `start()` blocks on `worker.join()` — both assumptions valid for a single vault, invalid for N.

**Decision**: `Main` loads `vaults.json` via `VaultService`, then for each `Vault`:
1. derives a `Properties` copy with `vault.path` set to that vault's path
2. derives a vault-scoped `LogService` via `withVault(repoSlug)`
3. creates a dedicated `SyncEventQueue`
4. constructs a `SyncOrchestrator` with the derived properties/log/queue

Each orchestrator's blocking `start()` runs on its own `Thread`; `Main`'s main thread joins all of them. The shutdown hook (DTR-021) is extended: `scheduler.stop()` → `broadcaster.interrupt()` → `orchestrators.forEach(stop)` → `logService.close()`.

**Rationale**: one `SyncOrchestrator` per vault preserves the "Git is serial" guarantee (DTR-011) per-repository while allowing concurrent execution across different vaults' repositories. Threading the blocking `start()` calls is the minimal change preserving the orchestrator's existing single-vault contract.

---

## DTR-041 — Configuration property naming: `path.backup` / `path.conflicts`

**Milestone**: M6 (multi-vault wiring)
**Status**: Accepted

**Context**: an intermediate `VaultService` draft read `backup.path`/`conflicts.path`, inconsistent with the `# Paths` section convention in `config.properties.template` (`path.vaults`, `path.backup`, `path.conflicts` — all `path.*` prefixed by domain).

**Decision**: `VaultService` reads `path.backup`/`path.conflicts`, matching the existing `path.vaults` convention and the established `config.properties.template`. No rename of the config file — the code was the outlier.

**Rationale**: `config.properties.template` is the established source of truth, with `path.*` as the domain-prefix convention for all path-related settings (`path.vaults`, `path.backup`, `path.conflicts`). The intermediate `VaultService` draft inverted the prefix — fixed before it propagated further. Property names are used in exactly one place (`VaultService`'s constructor), so the fix has zero impact on the 106-test suite.

---

## DTR-042 — JavaFX over Swing for MainWindow

**Milestone**: M6 (original UI scope, deferred)
**Status**: Planned — deferred pending multi-vault MainWindow design

**Context**: Swing is abandoned and visually dated. JavaFX is actively maintained, CSS-styleable, and designed for the scene-graph model.

**Decision**: JavaFX for all `MainWindow` components. AWT retained only for `SystemTray`/`TrayIcon` — no JavaFX equivalent exists. Coexistence via `Platform.setImplicitExit(false)`; all JavaFX updates from AWT threads via `Platform.runLater()`.

**Evaluated alternatives**:
- Swing — rejected, no CSS theming, no scene graph, no active development

**Rationale**: CSS theming is a first-class `ForgeUI` requirement. JavaFX delivers it natively.

---

## DTR-043 — MainWindow — six tabs, contextual opening

**Milestone**: M6 (original UI scope, deferred)
**Status**: Planned — tab structure to be revisited for multi-vault navigation

**Context**: MainWindow must serve non-technical users (dashboard) and advanced users (per-vault config, log). Contextual opening from ContextMenu reduces navigation steps.

**Decision**: single `TabPane` with six tabs:

```
[ 🏠 Home ]  [ Properties ]  [ Log ]  [ Conflicts ]  [ Backup ]  [ ⚙ Settings ]
```

Vault switcher (`ComboBox` autocomplete) always visible in toolbar. Contextual opening from AWT thread:

```java
Platform.runLater(() -> mainWindow.openTab(Tab.LOG, vaultId));
```

Conflict resolution dialog in Tab Conflicts:

```
Have you copied the remote changes you wanted to keep?
Once confirmed, the remote version will be deleted.

[ Cancel ]    [ Yes, I'm done ]
```

---

## DTR-044 — ForgeUI — shared JavaFX design system, Maven Central candidate

**Milestone**: M6 (original UI scope, deferred)
**Status**: Planned

**Context**: NomadSync is the first product in a planned family of Java desktop applications. A shared JavaFX library enables consistent theming.

**Decision**: separate Maven project `forge-ui`. Three themes via CSS swap:

```java
scene.getStylesheets().clear();
scene.getStylesheets().add(theme.cssPath());
```

Themes: Default, Retro terminal, Zen minimal. Name finalised: **ForgeUI**.

**Rationale**: CSS theming in JavaFX is a single method call. Impossible in Swing without third-party LAF libraries.

---

## DTR-045 — i18n — 10 languages, ResourceBundle

**Milestone**: M6 (original UI scope, deferred)
**Status**: Planned

**Decision**: 10 locale files covering ~75% of global internet users: English, Mandarin, Hindi, Spanish, Arabic, Portuguese, French, German, Japanese, Italian.

RTL (Arabic): `root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT)` — propagates to all children automatically in JavaFX.

---

## DTR-046 — VaultService — vault name uniqueness constraint

**Milestone**: M5
**Status**: Planned — not yet enforced in current `VaultService` implementation

**Context**: backup and remote-conflicts folder names derive from `vault.name`. Duplicates create ambiguous folders during manual conflict resolution.

**Decision**: `VaultService.create()` and `update()` throw `VaultException("duplicated vault name: " + name)` if the name already exists. On startup, all names in `vaults.json` are validated — duplicates block startup with a notification.

**Rationale**: fail fast on inconsistent state, before it manifests as ambiguous filesystem paths.