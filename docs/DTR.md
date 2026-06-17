# NomadSync — Decision Track Record

> All significant technical and architectural decisions made during the project,
> with rationale and evaluated alternatives. Updated as the project evolves.

---

## DTR-001 — Git-based sync instead of cloud storage

**Milestone**: M1 | **Status**: Accepted

**Context**: folders need to stay in sync across multiple machines. Cloud sync
services are incompatible with applications that hold files open, due to
asynchronous and concurrent file modifications.

**Decision**: Git + GitHub.

**Rationale**: pull/push operations are atomic and synchronous; native diff
enables differential autosave; no external process touches the files while
applications are open.

**Evaluated alternatives**: OneDrive direct sync (causes conflicts due to
concurrent writes); Syncthing (P2P, requires at least one device to always be
on); Obsidian Sync (paid ~$4/month).

---

## DTR-002 — `theirs` conflict resolution strategy on pull

**Milestone**: M1 | **Status**: Superseded by DTR-013 (M5)

**Context**: the pull runs automatically at logon without user supervision.

**Decision**: `git pull -X theirs` — on conflict, the remote version always wins.

**Accepted risks**: uncommitted local changes could be lost in case of a conflict
before push. Mitigated by periodic autosave and `git stash` before pull.

---

## DTR-003 — Logon sequence: stash → pull → stash pop

**Milestone**: M1 | **Status**: Accepted

**Decision**: run `git stash` before pull and `git stash pop` after.

**Rationale**: preserves local changes during pull without requiring an explicit
commit. **Evaluated alternatives**: `git reset --hard` — rejected, destroys
local changes with no recovery.

---

## DTR-004 — Logon/logoff hook via Task Scheduler

**Milestone**: M1 | **Status**: Accepted

**Decision**: Task Scheduler (`taskschd.msc`). Available on all Windows
editions including Home; inspectable UI; execution history; XML task export.

**Evaluated alternatives**: Group Policy Editor — rejected, not available on
Windows Home.

---

## DTR-005 — Differential autosave via `git diff --quiet`

**Milestone**: M1 | **Status**: Accepted

**Decision**: `git diff --quiet` as guard — exit code 0 = no changes, exit
code 1 = changes present. Prevents empty commits.

---

## DTR-006 — Fat JAR via `maven-assembly-plugin`

**Milestone**: M1 | **Status**: Accepted

**Decision**: `maven-assembly-plugin` with `jar-with-dependencies`. Single
self-contained artifact; deploy by copying `target/`.

---

## DTR-007 — Resources copied to `target/` via `maven-resources-plugin`

**Milestone**: M1 | **Status**: Accepted

**Decision**: `maven-resources-plugin` copies from `src/main/resources/` to
`target/` during `package`. Self-contained deployment, no manual post-build steps.

---

## DTR-008 — Separate configuration files per environment

**Milestone**: M1 | **Status**: Accepted

**Decision**: `config.dev.properties` / `config.prod.properties` excluded via
`.gitignore`; `config.properties.template` committed as reference. `vaults.json`
also excluded — only `vaults.json.template` committed.

---

## DTR-009 — Thread-safe logging via `synchronized`

**Milestone**: M1 | **Status**: Superseded by DTR-034 (M5)

**Decision**: `LogService.log()` declared `synchronized`. Simple and correct
for two threads at most.

---

## DTR-010 — `SyncOrchestrator` as intermediate layer

**Milestone**: M1 | **Status**: Accepted

**Decision**: `SyncOrchestrator` between `Main` and `GitService`. Coordination
logic (stash before pull, exit code handling) belongs to neither.

---

## DTR-011 — Event-driven architecture for SyncOrchestrator

**Milestone**: M2 | **Status**: Accepted

**Decision**: priority queue. Callers publish events; orchestrator consumes
serially. Git is serial — concurrent operations on the same repository cause
conflicts. **Evaluated alternatives**: direct calls — rejected, concurrency
handling spreads across codebase.

---

## DTR-012 — Event priority scale (original)

**Milestone**: M2 | **Status**: Superseded by DTR-013 (M5)

Original scale: PULL_LOGON(1), PUSH_MANUAL(2), PUSH_LOGOFF(3), AUTOSAVE(4).

---

## DTR-013 — SYNCHRONIZE replaces PULL_MANUAL and PUSH_MANUAL

**Milestone**: M5 | **Status**: Accepted — supersedes DTR-012

**Decision**: single `SYNCHRONIZE` event. Scale:

| Priority | Event |
|---|---|
| 1 | `PULL_LOGON` |
| 2 | `SYNCHRONIZE` |
| 3 | `PUSH_LOGOFF` |
| 4 | `COMMIT_MANUAL` |
| 5 | `AUTOSAVE` |

COMMIT_MANUAL and AUTOSAVE added in M7 (DTR-048).

---

## DTR-014 — Deduplication: latest wins

**Milestone**: M2 | **Status**: Accepted

**Decision**: a queued event is replaced by the most recent of the same type.
In-flight event is not interrupted.

---

## DTR-015 — Retry with exponential backoff

**Milestone**: M2 | **Status**: Accepted

**Decision**: max 3 attempts. Delay: 30s → 60s → 120s. After third failure,
event discarded and `NotificationHook.onFailure` invoked.

---

## DTR-016 — Notification hook as dependency inversion

**Milestone**: M2 | **Status**: Accepted

**Decision**: `NotificationHook` interface. Default implementation writes to
log. Tray attaches by implementing the interface without modifying the
orchestrator.

---

## DTR-017 — Separation of local commit and remote push

**Milestone**: M2 | **Status**: Accepted

**Decision**: `AUTOSAVE` → local commit only. `PUSH_LOGOFF` / `SYNCHRONIZE`
→ local commit + remote push. Exponential backoff applies only to remote
operations.

---

## DTR-018 — `hasUncommittedChanges()` guard before stash/stashPop

**Milestone**: M2 | **Status**: Accepted

**Decision**: `git status --porcelain` guard before `stash()`/`stashPop()`.
`git diff --quiet` insufficient — does not detect staged changes.

---

## DTR-019 — `notify()` renamed to `onFailure()` in NotificationHook

**Milestone**: M2 | **Status**: Accepted

**Decision**: `onFailure(SyncEvent, String message)`. Avoids collision with
`Object.notify()`; `message` parameter communicates failure cause to tray.

---

## DTR-020 — Plain Thread instead of ExecutorService for worker loop

**Milestone**: M2 | **Status**: Accepted

**Decision**: plain `Thread` instantiated internally. `ExecutorService` adds no
value for a single infinite-loop worker. Shutdown via `interrupt()` + `join()`.

---

## DTR-021 — Shutdown hook registered in Main

**Milestone**: M2 | **Status**: Accepted — extended in M6 (DTR-040), M7 (DTR-050)

**Decision**: shutdown hook in `Main`. Order: `scheduler.stop()` →
`broadcaster.interrupt()` → `orchestrators.forEach(stop)` → `logService.close()`.

---

## DTR-022 — Git Flow as branching strategy

**Milestone**: M2 | **Status**: Accepted

**Decision**: Git Flow AVH Edition. `main` (releases only), `develop`
(integration), `feature/*` (per grooming objective), `release/*`, `hotfix/*`.

---

## DTR-023 — `CommandUtil` as shared process execution utility

**Milestone**: M3 | **Status**: Accepted — extended in M5 (DTR-037), M7 (DTR-049)

**Decision**: static `CommandUtil` in `util` package. Optional `LogService`
parameter; overload without for test helpers.

---

## DTR-024 — Package-private test constructors on `SyncEvent` and `AutosaveScheduler`

**Milestone**: M3 | **Status**: Accepted

**Decision**: `SyncEvent` package-private constructor with controlled timestamp.
`AutosaveScheduler` package-private constructor with `TimeUnit` parameter for
sub-minute test intervals.

---

## DTR-025 — `SyncEventQueue` as real instance in `SyncOrchestratorTest`

**Milestone**: M3 | **Status**: Accepted

**Decision**: real `SyncEventQueue`, mock only `GitService` and
`NotificationHook`. Pure logic with no side effects — ideal real instance.

---

## DTR-025b — JDK downgrade from 25 to 21

**Milestone**: M3 | **Status**: Accepted

**Decision**: Oracle OpenJDK 21 LTS. Mockito 5.12.0 / ByteBuddy did not
support Java 25 class files.

---

## DTR-025c — Testing conventions: empty-file and timer resolution

**Milestone**: M3 | **Status**: Accepted

**Decision**: `readLogFile` returns empty string if file absent. Minimum
`Thread.sleep(50)` in scheduler tests — Windows timer resolution ~15ms.

---

## DTR-026 — SYNCHRONIZE conflict strategy: `-X ours` with FIFO backup

**Milestone**: M5 | **Status**: Accepted

**Decision**: `-X ours`. Sequence: commit local → pull → on conflict: merge
--abort → FIFO backup → pull -X ours --no-edit → extract remote versions
via `git show FETCH_HEAD:<file>` → push.

Field-verified: `--no-pager` mandatory on `git show`; `--no-edit` mandatory
on `pull -X ours`; use `FETCH_HEAD` not `MERGE_HEAD`.

---

## DTR-027 — FIFO backup: maximum 3 snapshots per vault

**Milestone**: M5 | **Status**: Accepted

**Decision**: max 3 per vault. FIFO — oldest deleted when limit reached.
`backups/<owner>_<name>_<timestamp>/`. Excludes `.gitignore` patterns.

---

## DTR-028 — TrayIcon: four visual states

**Milestone**: M5 | **Status**: Planned — backend ready, UI deferred to M8+

Idle (green), Syncing (animated), Error (red), Conflict (orange).

---

## DTR-029 — ContextMenu: zero cognitive decisions

**Milestone**: M5 | **Status**: Planned — UI deferred to M8+

AWT `PopupMenu` with sync/pull/log/folder actions. Labels describe outcomes,
not Git operations.

---

## DTR-030 — VaultSwitcherPanel and notifications

**Milestone**: M5 | **Status**: Planned — UI deferred to M8+

`CheckboxMenuItem` per vault. JavaFX `Dialog<R>`/`Alert` for persistent
conflict/network-failure notifications (not Swing `JDialog`).

---

## DTR-031 — Vault identity: `owner` field and derived `repoSlug`

**Milestone**: M5/M6 | **Status**: Accepted — per-vault credentials implemented M7

**Decision**: `Vault.owner` + `getRepoSlug()` derived as `<owner>/<name>`.
Not persisted separately. Used as `universalId` in log entries, snapshot/conflict
directory prefix, CLI resolution key.

**Per-vault credentials** (closed M7): `gitName`, `gitEmail`, `gitUsername`,
`gitToken`, `gitBranch`, `gitRemote` — optional fields, resolved via
`StringUtil.coalesce(vaultValue, globalValue)`. Applied at bootstrap via
`GitService.bootstrapVault(Vault)`.

---

## DTR-032 — Domain/DTO separation: no Jackson annotations in domain classes

**Milestone**: M5/M6 | **Status**: Accepted

**Decision**: Jackson annotations in `dto` package only. `toDomain()`/
`fromDomain()` as uniform naming convention. `JsonMapper` is the only class
that interacts with both layers.

---

## DTR-033 — `VaultContext` as a record

**Milestone**: M5/M6 | **Status**: Accepted

**Decision**: Java record with four components (`Vault`, `SyncEventQueue`,
`SyncOrchestrator`, `ScheduledFuture`). Set once, never replaced.

---

## DTR-034 — `LogService`: multi-writer fan-out and vault scoping

**Milestone**: M5/M6 | **Status**: Accepted — supersedes DTR-009

**Decision**: `List<LogWriter>` from `log.writers` property. `withVault(repoSlug)`
returns new instance sharing same writers. Thread-safety per writer:
`FileLogWriter` uses `synchronized`; `SeqHttpLogWriter` uses `BlockingQueue`.
`InMemoryLogWriter` not configurable — instantiated directly by runtime code.

---

## DTR-035 — `SeqHttpLogWriter`: async via internal queue and daemon thread

**Milestone**: M5/M6 | **Status**: Accepted

**Decision**: `BlockingQueue` (capacity 1000) + daemon thread `seq-log-writer`.
Queue full → event dropped silently to `stderr`. `close()` drains queue (5s
timeout) then interrupts worker.

---

## DTR-036 — `GitignoreService`: stateless, type-safe pattern casting

**Milestone**: M5/M6 | **Status**: Accepted

**Decision**: no mutable instance state — thread-safe across concurrent vaults.
Type-safe cast via `filter(isInstance).map(cast)`.

---

## DTR-037 — `CommandUtil.runCommandToFile`: binary-safe OS redirection

**Milestone**: M5/M6 | **Status**: Accepted — extends DTR-023

**Decision**: `ProcessBuilder.redirectOutput(File)` — OS writes directly to
file, bypasses JVM heap. Mandatory for binary content from `git show`.

---

## DTR-038 — `VaultService.saveConflict`: atomic move from temp file

**Milestone**: M5/M6 | **Status**: Accepted

**Decision**: `Files.move` with `REPLACE_EXISTING` — atomic on same-filesystem
moves. Caller wraps in `try/finally` with `deleteIfExists` as safety net.

---

## DTR-039 — Broadcast queue and dispatcher for cross-vault events

**Milestone**: M6 | **Status**: Accepted

**Decision**: single broadcast `SyncEventQueue`. Broadcaster thread routes:
`vaultId == null` → fan-out all; `vaultId != null` → matching queue only.

---

## DTR-040 — Per-vault `SyncOrchestrator` wiring, threaded startup

**Milestone**: M6 | **Status**: Accepted — extends DTR-021, updated M7

**Decision**: one `SyncOrchestrator` per vault. Each `start()` runs on its own
thread; main thread joins all. M7: `SyncOrchestrator` receives `Vault` directly
instead of `Properties`.

---

## DTR-041 — Configuration property naming

**Milestone**: M6 | **Status**: Accepted

**Decision**: `path.backup` → `backup.path`, `path.conflicts` → `conflicts.path`.
Code is source of truth.

---

## DTR-042 — JavaFX over Swing for MainWindow

**Milestone**: M6 | **Status**: Planned — UI deferred to M8+

**Decision**: JavaFX for all `MainWindow` components. AWT retained only for
`SystemTray`/`TrayIcon` (no JavaFX equivalent). Coexistence via
`Platform.setImplicitExit(false)` and `Platform.runLater()`.

---

## DTR-043 — MainWindow: six tabs, contextual opening

**Milestone**: M6 | **Status**: Planned — UI deferred to M8+

`TabPane`: Home, Properties, Log, Conflicts, Backup, Settings. Vault switcher
in toolbar. Contextual opening from AWT thread via `Platform.runLater()`.

---

## DTR-044 — ForgeUI: shared JavaFX design system

**Milestone**: M6 | **Status**: In progress (separate project)

**Decision**: separate Maven project `forgeui`. Three themes via CSS swap.
Maven Central candidate.

---

## DTR-045 — i18n: 10 languages, ResourceBundle

**Milestone**: M6 | **Status**: Planned — UI deferred to M8+

10 locales covering ~75% of global internet users. RTL via
`NodeOrientation.RIGHT_TO_LEFT`.

---

## DTR-046 — VaultService: uniqueness constraints

**Milestone**: M5/M7 | **Status**: Accepted — implemented M7

**Context**: backup/conflict directory names originally derived from `vault.name`
alone, allowing two vaults with same name but different owners to collide on the
same snapshot directories.

**Decision**: uniqueness enforced on `repoSlug` (`owner/name`), not `name`.
Separate uniqueness constraint added for `path`. Both enforced in `create()`,
`update()`, and `load()`. Snapshot/conflict directory prefix changed to
`<owner>_<name>_<timestamp>`.

---

## DTR-047 — CLI: flag-based arguments instead of positional

**Milestone**: M7 | **Status**: Accepted

**Context**: positional arguments are ambiguous when multiple optional parameters
exist (e.g. `properties_file`, `vaultId`, `messagePath`).

**Decision**: `operation` positional (args[0]); all others as `--key=value` flags
in any order. `--config` defaults to `./config.properties`. Zero external
dependencies — manual parsing via `Arrays.stream(args).skip(1)`.

---

## DTR-048 — `EventType.mandatoryVault`: broadcast vs. error dispatch

**Milestone**: M7 | **Status**: Accepted

**Context**: `COMMIT_MANUAL` carries a user-provided message meaningful only for
a single repository — broadcast is nonsensical.

**Decision**: `boolean mandatoryVault` field on `EventType`. `--vault` absent +
`mandatoryVault=true` → `System.exit(1)` with error. `--vault` absent +
`mandatoryVault=false` → broadcast. Only `COMMIT_MANUAL` is mandatory.

Priority scale updated: `COMMIT_MANUAL(4, true)`, `AUTOSAVE(5, false)`.

---

## DTR-049 — Token security: sensitive args masking in CommandUtil

**Milestone**: M7 | **Status**: Accepted

**Context**: GitHub token embedded in remote URL (`https://<token>@github.com/...`)
must not appear in log output. `CommandUtil` logs the full command string.

**Decision**: `CommandUtil.runCommand` overload with `Set<String> sensitiveArgs`.
Arguments matching the set are replaced with `<hidden>` in the log; the process
receives the real values unchanged. `bootstrapVault` passes `Set.of(remoteUrl)`.
`handleConfig` in `Main` logs `git.token=<hidden>`.

**Security property**: token appears only in `.git/config` (local, never committed)
— never as a command-line argument, never in log files.

---

## DTR-050 — Daemon flag: one-shot vs. long-running process

**Milestone**: M7 | **Status**: Accepted

**Context**: CLI operations (`pull`, `push`, `sync`, `commit`) should terminate
automatically after completion when launched from a terminal. The Tray requires
the process to stay alive indefinitely.

**Decision**: `--daemon` flag in `Main`. Without it: after publishing the event,
`awaitIdle()` polls all per-vault queues every 250ms; on empty + 500ms settling
delay, calls `System.exit(0)`. With `--daemon`: blocks on `worker.join()` as
before. Early-exit operations (`status`, `config`) always exit immediately.

**Rationale**: single binary, two modes. Task Scheduler uses one-shot (no flag);
Tray uses `--daemon`.

---

## DTR-051 — `NomadSync status`: early-exit, output on System.out

**Milestone**: M7 | **Status**: Accepted

**Context**: `git status` output is a human-readable interactive response, not
a system event.

**Decision**: `status` is an early-exit operation — no orchestrators, no
scheduler. `GitService.status(Vault)` uses `runCommandWithLines` (preserves
newlines, unlike `runCommandWithOutput`). Output printed to `System.out`; errors
to `logService.error`. Without `--vault`: broadcasts across all vaults with
`=== repoSlug ===` headers.

---

## DTR-052 — `NomadProperties`/`NomadPropertiesLoader`: ForgeUI pattern applied

**Milestone**: M7 | **Status**: Accepted

**Context**: magic strings for property keys scattered across `Main`, `LogService`,
`GitService`, `VaultService`, `SocketClient`, `SocketServer`, `AutosaveScheduler`,
`SeqHttpLogWriter`.

**Decision**: `NomadProperties` (key registry, nested classes by domain: `Git`,
`Path`, `Log`, `Autosave`, `Commit`, `Socket`) and `NomadPropertiesLoader`
(classpath loader, `getBoolean`, `getEnum`) — identical pattern to ForgeUI
`ForgeProperties`/`ForgePropertiesLoader`. All magic strings eliminated.

---

# NomadSync — Decision Track Record

> All significant technical and architectural decisions made during the project,
> with rationale and evaluated alternatives. Updated as the project evolves.

---

## DTR-001 — Git-based sync instead of cloud storage

**Milestone**: M1 | **Status**: Accepted

**Context**: folders need to stay in sync across multiple machines. Cloud sync
services are incompatible with applications that hold files open, due to
asynchronous and concurrent file modifications.

**Decision**: Git + GitHub.

**Rationale**: pull/push operations are atomic and synchronous; native diff
enables differential autosave; no external process touches the files while
applications are open.

**Evaluated alternatives**: OneDrive direct sync (causes conflicts due to
concurrent writes); Syncthing (P2P, requires at least one device to always be
on); Obsidian Sync (paid ~$4/month).

---

## DTR-002 — `theirs` conflict resolution strategy on pull

**Milestone**: M1 | **Status**: Superseded by DTR-013 (M5)

**Context**: the pull runs automatically at logon without user supervision.

**Decision**: `git pull -X theirs` — on conflict, the remote version always wins.

**Accepted risks**: uncommitted local changes could be lost in case of a conflict
before push. Mitigated by periodic autosave and `git stash` before pull.

---

## DTR-003 — Logon sequence: stash → pull → stash pop

**Milestone**: M1 | **Status**: Accepted

**Decision**: run `git stash` before pull and `git stash pop` after.

**Rationale**: preserves local changes during pull without requiring an explicit
commit. **Evaluated alternatives**: `git reset --hard` — rejected, destroys
local changes with no recovery.

---

## DTR-004 — Logon/logoff hook via Task Scheduler

**Milestone**: M1 | **Status**: Accepted

**Decision**: Task Scheduler (`taskschd.msc`). Available on all Windows
editions including Home; inspectable UI; execution history; XML task export.

**Evaluated alternatives**: Group Policy Editor — rejected, not available on
Windows Home.

---

## DTR-005 — Differential autosave via `git diff --quiet`

**Milestone**: M1 | **Status**: Accepted

**Decision**: `git diff --quiet` as guard — exit code 0 = no changes, exit
code 1 = changes present. Prevents empty commits.

---

## DTR-006 — Fat JAR via `maven-assembly-plugin`

**Milestone**: M1 | **Status**: Accepted

**Decision**: `maven-assembly-plugin` with `jar-with-dependencies`. Single
self-contained artifact; deploy by copying `target/`.

---

## DTR-007 — Resources copied to `target/` via `maven-resources-plugin`

**Milestone**: M1 | **Status**: Accepted

**Decision**: `maven-resources-plugin` copies from `src/main/resources/` to
`target/` during `package`. Self-contained deployment, no manual post-build steps.

---

## DTR-008 — Separate configuration files per environment

**Milestone**: M1 | **Status**: Accepted

**Decision**: `config.dev.properties` / `config.prod.properties` excluded via
`.gitignore`; `config.properties.template` committed as reference. `vaults.json`
also excluded — only `vaults.json.template` committed.

---

## DTR-009 — Thread-safe logging via `synchronized`

**Milestone**: M1 | **Status**: Superseded by DTR-034 (M5)

**Decision**: `LogService.log()` declared `synchronized`. Simple and correct
for two threads at most.

---

## DTR-010 — `SyncOrchestrator` as intermediate layer

**Milestone**: M1 | **Status**: Accepted

**Decision**: `SyncOrchestrator` between `Main` and `GitService`. Coordination
logic (stash before pull, exit code handling) belongs to neither.

---

## DTR-011 — Event-driven architecture for SyncOrchestrator

**Milestone**: M2 | **Status**: Accepted

**Decision**: priority queue. Callers publish events; orchestrator consumes
serially. Git is serial — concurrent operations on the same repository cause
conflicts. **Evaluated alternatives**: direct calls — rejected, concurrency
handling spreads across codebase.

---

## DTR-012 — Event priority scale (original)

**Milestone**: M2 | **Status**: Superseded by DTR-013 (M5)

Original scale: PULL_LOGON(1), PUSH_MANUAL(2), PUSH_LOGOFF(3), AUTOSAVE(4).

---

## DTR-013 — SYNCHRONIZE replaces PULL_MANUAL and PUSH_MANUAL

**Milestone**: M5 | **Status**: Accepted — supersedes DTR-012

**Decision**: single `SYNCHRONIZE` event. Scale:

| Priority | Event |
|---|---|
| 1 | `PULL_LOGON` |
| 2 | `SYNCHRONIZE` |
| 3 | `PUSH_LOGOFF` |
| 4 | `COMMIT_MANUAL` |
| 5 | `AUTOSAVE` |

COMMIT_MANUAL and AUTOSAVE added in M7 (DTR-048).

---

## DTR-014 — Deduplication: latest wins

**Milestone**: M2 | **Status**: Accepted

**Decision**: a queued event is replaced by the most recent of the same type.
In-flight event is not interrupted.

---

## DTR-015 — Retry with exponential backoff

**Milestone**: M2 | **Status**: Accepted

**Decision**: max 3 attempts. Delay: 30s → 60s → 120s. After third failure,
event discarded and `NotificationHook.onFailure` invoked.

---

## DTR-016 — Notification hook as dependency inversion

**Milestone**: M2 | **Status**: Accepted

**Decision**: `NotificationHook` interface. Default implementation writes to
log. Tray attaches by implementing the interface without modifying the
orchestrator.

---

## DTR-017 — Separation of local commit and remote push

**Milestone**: M2 | **Status**: Accepted

**Decision**: `AUTOSAVE` → local commit only. `PUSH_LOGOFF` / `SYNCHRONIZE`
→ local commit + remote push. Exponential backoff applies only to remote
operations.

---

## DTR-018 — `hasUncommittedChanges()` guard before stash/stashPop

**Milestone**: M2 | **Status**: Accepted

**Decision**: `git status --porcelain` guard before `stash()`/`stashPop()`.
`git diff --quiet` insufficient — does not detect staged changes.

---

## DTR-019 — `notify()` renamed to `onFailure()` in NotificationHook

**Milestone**: M2 | **Status**: Accepted

**Decision**: `onFailure(SyncEvent, String message)`. Avoids collision with
`Object.notify()`; `message` parameter communicates failure cause to tray.

---

## DTR-020 — Plain Thread instead of ExecutorService for worker loop

**Milestone**: M2 | **Status**: Accepted

**Decision**: plain `Thread` instantiated internally. `ExecutorService` adds no
value for a single infinite-loop worker. Shutdown via `interrupt()` + `join()`.

---

## DTR-021 — Shutdown hook registered in Main

**Milestone**: M2 | **Status**: Accepted — extended in M6 (DTR-040), M7 (DTR-050)

**Decision**: shutdown hook in `Main`. Order: `scheduler.stop()` →
`broadcaster.interrupt()` → `orchestrators.forEach(stop)` → `logService.close()`.

---

## DTR-022 — Git Flow as branching strategy

**Milestone**: M2 | **Status**: Accepted

**Decision**: Git Flow AVH Edition. `main` (releases only), `develop`
(integration), `feature/*` (per grooming objective), `release/*`, `hotfix/*`.

---

## DTR-023 — `CommandUtil` as shared process execution utility

**Milestone**: M3 | **Status**: Accepted — extended in M5 (DTR-037), M7 (DTR-049)

**Decision**: static `CommandUtil` in `util` package. Optional `LogService`
parameter; overload without for test helpers.

---

## DTR-024 — Package-private test constructors on `SyncEvent` and `AutosaveScheduler`

**Milestone**: M3 | **Status**: Accepted

**Decision**: `SyncEvent` package-private constructor with controlled timestamp.
`AutosaveScheduler` package-private constructor with `TimeUnit` parameter for
sub-minute test intervals.

---

## DTR-025 — `SyncEventQueue` as real instance in `SyncOrchestratorTest`

**Milestone**: M3 | **Status**: Accepted

**Decision**: real `SyncEventQueue`, mock only `GitService` and
`NotificationHook`. Pure logic with no side effects — ideal real instance.

---

## DTR-025b — JDK downgrade from 25 to 21

**Milestone**: M3 | **Status**: Accepted

**Decision**: Oracle OpenJDK 21 LTS. Mockito 5.12.0 / ByteBuddy did not
support Java 25 class files.

---

## DTR-025c — Testing conventions: empty-file and timer resolution

**Milestone**: M3 | **Status**: Accepted

**Decision**: `readLogFile` returns empty string if file absent. Minimum
`Thread.sleep(50)` in scheduler tests — Windows timer resolution ~15ms.

---

## DTR-026 — SYNCHRONIZE conflict strategy: `-X ours` with FIFO backup

**Milestone**: M5 | **Status**: Accepted

**Decision**: `-X ours`. Sequence: commit local → pull → on conflict: merge
--abort → FIFO backup → pull -X ours --no-edit → extract remote versions
via `git show FETCH_HEAD:<file>` → push.

Field-verified: `--no-pager` mandatory on `git show`; `--no-edit` mandatory
on `pull -X ours`; use `FETCH_HEAD` not `MERGE_HEAD`.

---

## DTR-027 — FIFO backup: maximum 3 snapshots per vault

**Milestone**: M5 | **Status**: Accepted

**Decision**: max 3 per vault. FIFO — oldest deleted when limit reached.
`backups/<owner>_<name>_<timestamp>/`. Excludes `.gitignore` patterns.

---

## DTR-028 — TrayIcon: four visual states

**Milestone**: M5 | **Status**: Planned — backend ready, UI deferred to M8+

Idle (green), Syncing (animated), Error (red), Conflict (orange).

---

## DTR-029 — ContextMenu: zero cognitive decisions

**Milestone**: M5 | **Status**: Planned — UI deferred to M8+

AWT `PopupMenu` with sync/pull/log/folder actions. Labels describe outcomes,
not Git operations.

---

## DTR-030 — VaultSwitcherPanel and notifications

**Milestone**: M5 | **Status**: Planned — UI deferred to M8+

`CheckboxMenuItem` per vault. JavaFX `Dialog<R>`/`Alert` for persistent
conflict/network-failure notifications (not Swing `JDialog`).

---

## DTR-031 — Vault identity: `owner` field and derived `repoSlug`

**Milestone**: M5/M6 | **Status**: Accepted — per-vault credentials implemented M7

**Decision**: `Vault.owner` + `getRepoSlug()` derived as `<owner>/<name>`.
Not persisted separately. Used as `universalId` in log entries, snapshot/conflict
directory prefix, CLI resolution key.

**Per-vault credentials** (closed M7): `gitName`, `gitEmail`, `gitUsername`,
`gitToken`, `gitBranch`, `gitRemote` — optional fields, resolved via
`StringUtil.coalesce(vaultValue, globalValue)`. Applied at bootstrap via
`GitService.bootstrapVault(Vault)`.

---

## DTR-032 — Domain/DTO separation: no Jackson annotations in domain classes

**Milestone**: M5/M6 | **Status**: Accepted

**Decision**: Jackson annotations in `dto` package only. `toDomain()`/
`fromDomain()` as uniform naming convention. `JsonMapper` is the only class
that interacts with both layers.

---

## DTR-033 — `VaultContext` as a record

**Milestone**: M5/M6 | **Status**: Accepted

**Decision**: Java record with four components (`Vault`, `SyncEventQueue`,
`SyncOrchestrator`, `ScheduledFuture`). Set once, never replaced.

---

## DTR-034 — `LogService`: multi-writer fan-out and vault scoping

**Milestone**: M5/M6 | **Status**: Accepted — supersedes DTR-009

**Decision**: `List<LogWriter>` from `log.writers` property. `withVault(repoSlug)`
returns new instance sharing same writers. Thread-safety per writer:
`FileLogWriter` uses `synchronized`; `SeqHttpLogWriter` uses `BlockingQueue`.
`InMemoryLogWriter` not configurable — instantiated directly by runtime code.

---

## DTR-035 — `SeqHttpLogWriter`: async via internal queue and daemon thread

**Milestone**: M5/M6 | **Status**: Accepted

**Decision**: `BlockingQueue` (capacity 1000) + daemon thread `seq-log-writer`.
Queue full → event dropped silently to `stderr`. `close()` drains queue (5s
timeout) then interrupts worker.

---

## DTR-036 — `GitignoreService`: stateless, type-safe pattern casting

**Milestone**: M5/M6 | **Status**: Accepted

**Decision**: no mutable instance state — thread-safe across concurrent vaults.
Type-safe cast via `filter(isInstance).map(cast)`.

---

## DTR-037 — `CommandUtil.runCommandToFile`: binary-safe OS redirection

**Milestone**: M5/M6 | **Status**: Accepted — extends DTR-023

**Decision**: `ProcessBuilder.redirectOutput(File)` — OS writes directly to
file, bypasses JVM heap. Mandatory for binary content from `git show`.

---

## DTR-038 — `VaultService.saveConflict`: atomic move from temp file

**Milestone**: M5/M6 | **Status**: Accepted

**Decision**: `Files.move` with `REPLACE_EXISTING` — atomic on same-filesystem
moves. Caller wraps in `try/finally` with `deleteIfExists` as safety net.

---

## DTR-039 — Broadcast queue and dispatcher for cross-vault events

**Milestone**: M6 | **Status**: Accepted

**Decision**: single broadcast `SyncEventQueue`. Broadcaster thread routes:
`vaultId == null` → fan-out all; `vaultId != null` → matching queue only.

---

## DTR-040 — Per-vault `SyncOrchestrator` wiring, threaded startup

**Milestone**: M6 | **Status**: Accepted — extends DTR-021, updated M7

**Decision**: one `SyncOrchestrator` per vault. Each `start()` runs on its own
thread; main thread joins all. M7: `SyncOrchestrator` receives `Vault` directly
instead of `Properties`.

---

## DTR-041 — Configuration property naming

**Milestone**: M6 | **Status**: Accepted

**Decision**: `path.backup` → `backup.path`, `path.conflicts` → `conflicts.path`.
Code is source of truth.

---

## DTR-042 — JavaFX over Swing for MainWindow

**Milestone**: M6 | **Status**: Planned — UI deferred to M8+

**Decision**: JavaFX for all `MainWindow` components. AWT retained only for
`SystemTray`/`TrayIcon` (no JavaFX equivalent). Coexistence via
`Platform.setImplicitExit(false)` and `Platform.runLater()`.

---

## DTR-043 — MainWindow: six tabs, contextual opening

**Milestone**: M6 | **Status**: Planned — UI deferred to M8+

`TabPane`: Home, Properties, Log, Conflicts, Backup, Settings. Vault switcher
in toolbar. Contextual opening from AWT thread via `Platform.runLater()`.

---

## DTR-044 — ForgeUI: shared JavaFX design system

**Milestone**: M6 | **Status**: In progress (separate project)

**Decision**: separate Maven project `forgeui`. Three themes via CSS swap.
Maven Central candidate.

---

## DTR-045 — i18n: 10 languages, ResourceBundle

**Milestone**: M6 | **Status**: Planned — UI deferred to M8+

10 locales covering ~75% of global internet users. RTL via
`NodeOrientation.RIGHT_TO_LEFT`.

---

## DTR-046 — VaultService: uniqueness constraints

**Milestone**: M5/M7 | **Status**: Accepted — implemented M7

**Context**: backup/conflict directory names originally derived from `vault.name`
alone, allowing two vaults with same name but different owners to collide on the
same snapshot directories.

**Decision**: uniqueness enforced on `repoSlug` (`owner/name`), not `name`.
Separate uniqueness constraint added for `path`. Both enforced in `create()`,
`update()`, and `load()`. Snapshot/conflict directory prefix changed to
`<owner>_<name>_<timestamp>`.

---

## DTR-047 — CLI: flag-based arguments instead of positional

**Milestone**: M7 | **Status**: Accepted

**Context**: positional arguments are ambiguous when multiple optional parameters
exist (e.g. `properties_file`, `vaultId`, `messagePath`).

**Decision**: `operation` positional (args[0]); all others as `--key=value` flags
in any order. `--config` defaults to `./config.properties`. Zero external
dependencies — manual parsing via `Arrays.stream(args).skip(1)`.

---

## DTR-048 — `EventType.mandatoryVault`: broadcast vs. error dispatch

**Milestone**: M7 | **Status**: Accepted

**Context**: `COMMIT_MANUAL` carries a user-provided message meaningful only for
a single repository — broadcast is nonsensical.

**Decision**: `boolean mandatoryVault` field on `EventType`. `--vault` absent +
`mandatoryVault=true` → `System.exit(1)` with error. `--vault` absent +
`mandatoryVault=false` → broadcast. Only `COMMIT_MANUAL` is mandatory.

Priority scale updated: `COMMIT_MANUAL(4, true)`, `AUTOSAVE(5, false)`.

---

## DTR-049 — Token security: sensitive args masking in CommandUtil

**Milestone**: M7 | **Status**: Accepted

**Context**: GitHub token embedded in remote URL (`https://<token>@github.com/...`)
must not appear in log output. `CommandUtil` logs the full command string.

**Decision**: `CommandUtil.runCommand` overload with `Set<String> sensitiveArgs`.
Arguments matching the set are replaced with `<hidden>` in the log; the process
receives the real values unchanged. `bootstrapVault` passes `Set.of(remoteUrl)`.
`handleConfig` in `Main` logs `git.token=<hidden>`.

**Security property**: token appears only in `.git/config` (local, never committed)
— never as a command-line argument, never in log files.

---

## DTR-050 — Daemon flag: one-shot vs. long-running process

**Milestone**: M7 | **Status**: Accepted

**Context**: CLI operations (`pull`, `push`, `sync`, `commit`) should terminate
automatically after completion when launched from a terminal. The Tray requires
the process to stay alive indefinitely.

**Decision**: `--daemon` flag in `Main`. Without it: after publishing the event,
`awaitIdle()` polls all per-vault queues every 250ms; on empty + 500ms settling
delay, calls `System.exit(0)`. With `--daemon`: blocks on `worker.join()` as
before. Early-exit operations (`status`, `config`) always exit immediately.

**Rationale**: single binary, two modes. Task Scheduler uses one-shot (no flag);
Tray uses `--daemon`.

---

## DTR-051 — `NomadSync status`: early-exit, output on System.out

**Milestone**: M7 | **Status**: Accepted

**Context**: `git status` output is a human-readable interactive response, not
a system event.

**Decision**: `status` is an early-exit operation — no orchestrators, no
scheduler. `GitService.status(Vault)` uses `runCommandWithLines` (preserves
newlines, unlike `runCommandWithOutput`). Output printed to `System.out`; errors
to `logService.error`. Without `--vault`: broadcasts across all vaults with
`=== repoSlug ===` headers.

---

## DTR-052 — `NomadProperties`/`NomadPropertiesLoader`: ForgeUI pattern applied

**Milestone**: M7 | **Status**: Accepted

**Context**: magic strings for property keys scattered across `Main`, `LogService`,
`GitService`, `VaultService`, `SocketClient`, `SocketServer`, `AutosaveScheduler`,
`SeqHttpLogWriter`.

**Decision**: `NomadProperties` (key registry, nested classes by domain: `Git`,
`Path`, `Log`, `Autosave`, `Commit`, `Socket`) and `NomadPropertiesLoader`
(classpath loader, `getBoolean`, `getEnum`) — identical pattern to ForgeUI
`ForgeProperties`/`ForgePropertiesLoader`. All magic strings eliminated.

---

# NomadSync — Decision Track Record

> All significant technical and architectural decisions made during the project,
> with rationale and evaluated alternatives. Updated as the project evolves.

---

## DTR-001 — Git-based sync instead of cloud storage

**Milestone**: M1 | **Status**: Accepted

**Context**: folders need to stay in sync across multiple machines. Cloud sync
services are incompatible with applications that hold files open, due to
asynchronous and concurrent file modifications.

**Decision**: Git + GitHub.

**Rationale**: pull/push operations are atomic and synchronous; native diff
enables differential autosave; no external process touches the files while
applications are open.

**Evaluated alternatives**: OneDrive direct sync (causes conflicts due to
concurrent writes); Syncthing (P2P, requires at least one device to always be
on); Obsidian Sync (paid ~$4/month).

---

## DTR-002 — `theirs` conflict resolution strategy on pull

**Milestone**: M1 | **Status**: Superseded by DTR-013 (M5)

**Context**: the pull runs automatically at logon without user supervision.

**Decision**: `git pull -X theirs` — on conflict, the remote version always wins.

**Accepted risks**: uncommitted local changes could be lost in case of a conflict
before push. Mitigated by periodic autosave and `git stash` before pull.

---

## DTR-003 — Logon sequence: stash → pull → stash pop

**Milestone**: M1 | **Status**: Accepted

**Decision**: run `git stash` before pull and `git stash pop` after.

**Rationale**: preserves local changes during pull without requiring an explicit
commit. **Evaluated alternatives**: `git reset --hard` — rejected, destroys
local changes with no recovery.

---

## DTR-004 — Logon/logoff hook via Task Scheduler

**Milestone**: M1 | **Status**: Accepted

**Decision**: Task Scheduler (`taskschd.msc`). Available on all Windows
editions including Home; inspectable UI; execution history; XML task export.

**Evaluated alternatives**: Group Policy Editor — rejected, not available on
Windows Home.

---

## DTR-005 — Differential autosave via `git diff --quiet`

**Milestone**: M1 | **Status**: Accepted

**Decision**: `git diff --quiet` as guard — exit code 0 = no changes, exit
code 1 = changes present. Prevents empty commits.

---

## DTR-006 — Fat JAR via `maven-assembly-plugin`

**Milestone**: M1 | **Status**: Accepted

**Decision**: `maven-assembly-plugin` with `jar-with-dependencies`. Single
self-contained artifact; deploy by copying `target/`.

---

## DTR-007 — Resources copied to `target/` via `maven-resources-plugin`

**Milestone**: M1 | **Status**: Accepted

**Decision**: `maven-resources-plugin` copies from `src/main/resources/` to
`target/` during `package`. Self-contained deployment, no manual post-build steps.

---

## DTR-008 — Separate configuration files per environment

**Milestone**: M1 | **Status**: Accepted

**Decision**: `config.dev.properties` / `config.prod.properties` excluded via
`.gitignore`; `config.properties.template` committed as reference. `vaults.json`
also excluded — only `vaults.json.template` committed.

---

## DTR-009 — Thread-safe logging via `synchronized`

**Milestone**: M1 | **Status**: Superseded by DTR-034 (M5)

**Decision**: `LogService.log()` declared `synchronized`. Simple and correct
for two threads at most.

---

## DTR-010 — `SyncOrchestrator` as intermediate layer

**Milestone**: M1 | **Status**: Accepted

**Decision**: `SyncOrchestrator` between `Main` and `GitService`. Coordination
logic (stash before pull, exit code handling) belongs to neither.

---

## DTR-011 — Event-driven architecture for SyncOrchestrator

**Milestone**: M2 | **Status**: Accepted

**Decision**: priority queue. Callers publish events; orchestrator consumes
serially. Git is serial — concurrent operations on the same repository cause
conflicts. **Evaluated alternatives**: direct calls — rejected, concurrency
handling spreads across codebase.

---

## DTR-012 — Event priority scale (original)

**Milestone**: M2 | **Status**: Superseded by DTR-013 (M5)

Original scale: PULL_LOGON(1), PUSH_MANUAL(2), PUSH_LOGOFF(3), AUTOSAVE(4).

---

## DTR-013 — SYNCHRONIZE replaces PULL_MANUAL and PUSH_MANUAL

**Milestone**: M5 | **Status**: Accepted — supersedes DTR-012

**Decision**: single `SYNCHRONIZE` event. Scale:

| Priority | Event |
|---|---|
| 1 | `PULL_LOGON` |
| 2 | `SYNCHRONIZE` |
| 3 | `PUSH_LOGOFF` |
| 4 | `COMMIT_MANUAL` |
| 5 | `AUTOSAVE` |

COMMIT_MANUAL and AUTOSAVE added in M7 (DTR-048).

---

## DTR-014 — Deduplication: latest wins

**Milestone**: M2 | **Status**: Accepted

**Decision**: a queued event is replaced by the most recent of the same type.
In-flight event is not interrupted.

---

## DTR-015 — Retry with exponential backoff

**Milestone**: M2 | **Status**: Accepted

**Decision**: max 3 attempts. Delay: 30s → 60s → 120s. After third failure,
event discarded and `NotificationHook.onFailure` invoked.

---

## DTR-016 — Notification hook as dependency inversion

**Milestone**: M2 | **Status**: Accepted

**Decision**: `NotificationHook` interface. Default implementation writes to
log. Tray attaches by implementing the interface without modifying the
orchestrator.

---

## DTR-017 — Separation of local commit and remote push

**Milestone**: M2 | **Status**: Accepted

**Decision**: `AUTOSAVE` → local commit only. `PUSH_LOGOFF` / `SYNCHRONIZE`
→ local commit + remote push. Exponential backoff applies only to remote
operations.

---

## DTR-018 — `hasUncommittedChanges()` guard before stash/stashPop

**Milestone**: M2 | **Status**: Accepted

**Decision**: `git status --porcelain` guard before `stash()`/`stashPop()`.
`git diff --quiet` insufficient — does not detect staged changes.

---

## DTR-019 — `notify()` renamed to `onFailure()` in NotificationHook

**Milestone**: M2 | **Status**: Accepted

**Decision**: `onFailure(SyncEvent, String message)`. Avoids collision with
`Object.notify()`; `message` parameter communicates failure cause to tray.

---

## DTR-020 — Plain Thread instead of ExecutorService for worker loop

**Milestone**: M2 | **Status**: Accepted

**Decision**: plain `Thread` instantiated internally. `ExecutorService` adds no
value for a single infinite-loop worker. Shutdown via `interrupt()` + `join()`.

---

## DTR-021 — Shutdown hook registered in Main

**Milestone**: M2 | **Status**: Accepted — extended in M6 (DTR-040), M7 (DTR-050)

**Decision**: shutdown hook in `Main`. Order: `scheduler.stop()` →
`broadcaster.interrupt()` → `orchestrators.forEach(stop)` → `logService.close()`.

---

## DTR-022 — Git Flow as branching strategy

**Milestone**: M2 | **Status**: Accepted

**Decision**: Git Flow AVH Edition. `main` (releases only), `develop`
(integration), `feature/*` (per grooming objective), `release/*`, `hotfix/*`.

---

## DTR-023 — `CommandUtil` as shared process execution utility

**Milestone**: M3 | **Status**: Accepted — extended in M5 (DTR-037), M7 (DTR-049)

**Decision**: static `CommandUtil` in `util` package. Optional `LogService`
parameter; overload without for test helpers.

---

## DTR-024 — Package-private test constructors on `SyncEvent` and `AutosaveScheduler`

**Milestone**: M3 | **Status**: Accepted

**Decision**: `SyncEvent` package-private constructor with controlled timestamp.
`AutosaveScheduler` package-private constructor with `TimeUnit` parameter for
sub-minute test intervals.

---

## DTR-025 — `SyncEventQueue` as real instance in `SyncOrchestratorTest`

**Milestone**: M3 | **Status**: Accepted

**Decision**: real `SyncEventQueue`, mock only `GitService` and
`NotificationHook`. Pure logic with no side effects — ideal real instance.

---

## DTR-025b — JDK downgrade from 25 to 21

**Milestone**: M3 | **Status**: Accepted

**Decision**: Oracle OpenJDK 21 LTS. Mockito 5.12.0 / ByteBuddy did not
support Java 25 class files.

---

## DTR-025c — Testing conventions: empty-file and timer resolution

**Milestone**: M3 | **Status**: Accepted

**Decision**: `readLogFile` returns empty string if file absent. Minimum
`Thread.sleep(50)` in scheduler tests — Windows timer resolution ~15ms.

---

## DTR-026 — SYNCHRONIZE conflict strategy: `-X ours` with FIFO backup

**Milestone**: M5 | **Status**: Accepted

**Decision**: `-X ours`. Sequence: commit local → pull → on conflict: merge
--abort → FIFO backup → pull -X ours --no-edit → extract remote versions
via `git show FETCH_HEAD:<file>` → push.

Field-verified: `--no-pager` mandatory on `git show`; `--no-edit` mandatory
on `pull -X ours`; use `FETCH_HEAD` not `MERGE_HEAD`.

---

## DTR-027 — FIFO backup: maximum 3 snapshots per vault

**Milestone**: M5 | **Status**: Accepted

**Decision**: max 3 per vault. FIFO — oldest deleted when limit reached.
`backups/<owner>_<name>_<timestamp>/`. Excludes `.gitignore` patterns.

---

## DTR-028 — TrayIcon: four visual states

**Milestone**: M5 | **Status**: Planned — backend ready, UI deferred to M8+

Idle (green), Syncing (animated), Error (red), Conflict (orange).

---

## DTR-029 — ContextMenu: zero cognitive decisions

**Milestone**: M5 | **Status**: Planned — UI deferred to M8+

AWT `PopupMenu` with sync/pull/log/folder actions. Labels describe outcomes,
not Git operations.

---

## DTR-030 — VaultSwitcherPanel and notifications

**Milestone**: M5 | **Status**: Planned — UI deferred to M8+

`CheckboxMenuItem` per vault. JavaFX `Dialog<R>`/`Alert` for persistent
conflict/network-failure notifications (not Swing `JDialog`).

---

## DTR-031 — Vault identity: `owner` field and derived `repoSlug`

**Milestone**: M5/M6 | **Status**: Accepted — per-vault credentials implemented M7

**Decision**: `Vault.owner` + `getRepoSlug()` derived as `<owner>/<name>`.
Not persisted separately. Used as `universalId` in log entries, snapshot/conflict
directory prefix, CLI resolution key.

**Per-vault credentials** (closed M7): `gitName`, `gitEmail`, `gitUsername`,
`gitToken`, `gitBranch`, `gitRemote` — optional fields, resolved via
`StringUtil.coalesce(vaultValue, globalValue)`. Applied at bootstrap via
`GitService.bootstrapVault(Vault)`.

---

## DTR-032 — Domain/DTO separation: no Jackson annotations in domain classes

**Milestone**: M5/M6 | **Status**: Accepted

**Decision**: Jackson annotations in `dto` package only. `toDomain()`/
`fromDomain()` as uniform naming convention. `JsonMapper` is the only class
that interacts with both layers.

---

## DTR-033 — `VaultContext` as a record

**Milestone**: M5/M6 | **Status**: Accepted

**Decision**: Java record with four components (`Vault`, `SyncEventQueue`,
`SyncOrchestrator`, `ScheduledFuture`). Set once, never replaced.

---

## DTR-034 — `LogService`: multi-writer fan-out and vault scoping

**Milestone**: M5/M6 | **Status**: Accepted — supersedes DTR-009

**Decision**: `List<LogWriter>` from `log.writers` property. `withVault(repoSlug)`
returns new instance sharing same writers. Thread-safety per writer:
`FileLogWriter` uses `synchronized`; `SeqHttpLogWriter` uses `BlockingQueue`.
`InMemoryLogWriter` not configurable — instantiated directly by runtime code.

---

## DTR-035 — `SeqHttpLogWriter`: async via internal queue and daemon thread

**Milestone**: M5/M6 | **Status**: Accepted

**Decision**: `BlockingQueue` (capacity 1000) + daemon thread `seq-log-writer`.
Queue full → event dropped silently to `stderr`. `close()` drains queue (5s
timeout) then interrupts worker.

---

## DTR-036 — `GitignoreService`: stateless, type-safe pattern casting

**Milestone**: M5/M6 | **Status**: Accepted

**Decision**: no mutable instance state — thread-safe across concurrent vaults.
Type-safe cast via `filter(isInstance).map(cast)`.

---

## DTR-037 — `CommandUtil.runCommandToFile`: binary-safe OS redirection

**Milestone**: M5/M6 | **Status**: Accepted — extends DTR-023

**Decision**: `ProcessBuilder.redirectOutput(File)` — OS writes directly to
file, bypasses JVM heap. Mandatory for binary content from `git show`.

---

## DTR-038 — `VaultService.saveConflict`: atomic move from temp file

**Milestone**: M5/M6 | **Status**: Accepted

**Decision**: `Files.move` with `REPLACE_EXISTING` — atomic on same-filesystem
moves. Caller wraps in `try/finally` with `deleteIfExists` as safety net.

---

## DTR-039 — Broadcast queue and dispatcher for cross-vault events

**Milestone**: M6 | **Status**: Accepted

**Decision**: single broadcast `SyncEventQueue`. Broadcaster thread routes:
`vaultId == null` → fan-out all; `vaultId != null` → matching queue only.

---

## DTR-040 — Per-vault `SyncOrchestrator` wiring, threaded startup

**Milestone**: M6 | **Status**: Accepted — extends DTR-021, updated M7

**Decision**: one `SyncOrchestrator` per vault. Each `start()` runs on its own
thread; main thread joins all. M7: `SyncOrchestrator` receives `Vault` directly
instead of `Properties`.

---

## DTR-041 — Configuration property naming

**Milestone**: M6 | **Status**: Accepted

**Decision**: `path.backup` → `backup.path`, `path.conflicts` → `conflicts.path`.
Code is source of truth.

---

## DTR-042 — JavaFX over Swing for MainWindow

**Milestone**: M6 | **Status**: Planned — UI deferred to M8+

**Decision**: JavaFX for all `MainWindow` components. AWT retained only for
`SystemTray`/`TrayIcon` (no JavaFX equivalent). Coexistence via
`Platform.setImplicitExit(false)` and `Platform.runLater()`.

---

## DTR-043 — MainWindow: six tabs, contextual opening

**Milestone**: M6 | **Status**: Planned — UI deferred to M8+

`TabPane`: Home, Properties, Log, Conflicts, Backup, Settings. Vault switcher
in toolbar. Contextual opening from AWT thread via `Platform.runLater()`.

---

## DTR-044 — ForgeUI: shared JavaFX design system

**Milestone**: M6 | **Status**: In progress (separate project)

**Decision**: separate Maven project `forgeui`. Three themes via CSS swap.
Maven Central candidate.

---

## DTR-045 — i18n: 10 languages, ResourceBundle

**Milestone**: M6 | **Status**: Planned — UI deferred to M8+

10 locales covering ~75% of global internet users. RTL via
`NodeOrientation.RIGHT_TO_LEFT`.

---

## DTR-046 — VaultService: uniqueness constraints

**Milestone**: M5/M7 | **Status**: Accepted — implemented M7

**Context**: backup/conflict directory names originally derived from `vault.name`
alone, allowing two vaults with same name but different owners to collide on the
same snapshot directories.

**Decision**: uniqueness enforced on `repoSlug` (`owner/name`), not `name`.
Separate uniqueness constraint added for `path`. Both enforced in `create()`,
`update()`, and `load()`. Snapshot/conflict directory prefix changed to
`<owner>_<name>_<timestamp>`.

---

## DTR-047 — CLI: flag-based arguments instead of positional

**Milestone**: M7 | **Status**: Accepted

**Context**: positional arguments are ambiguous when multiple optional parameters
exist (e.g. `properties_file`, `vaultId`, `messagePath`).

**Decision**: `operation` positional (args[0]); all others as `--key=value` flags
in any order. `--config` defaults to `./config.properties`. Zero external
dependencies — manual parsing via `Arrays.stream(args).skip(1)`.

---

## DTR-048 — `EventType.mandatoryVault`: broadcast vs. error dispatch

**Milestone**: M7 | **Status**: Accepted

**Context**: `COMMIT_MANUAL` carries a user-provided message meaningful only for
a single repository — broadcast is nonsensical.

**Decision**: `boolean mandatoryVault` field on `EventType`. `--vault` absent +
`mandatoryVault=true` → `System.exit(1)` with error. `--vault` absent +
`mandatoryVault=false` → broadcast. Only `COMMIT_MANUAL` is mandatory.

Priority scale updated: `COMMIT_MANUAL(4, true)`, `AUTOSAVE(5, false)`.

---

## DTR-049 — Token security: sensitive args masking in CommandUtil

**Milestone**: M7 | **Status**: Accepted

**Context**: GitHub token embedded in remote URL (`https://<token>@github.com/...`)
must not appear in log output. `CommandUtil` logs the full command string.

**Decision**: `CommandUtil.runCommand` overload with `Set<String> sensitiveArgs`.
Arguments matching the set are replaced with `<hidden>` in the log; the process
receives the real values unchanged. `bootstrapVault` passes `Set.of(remoteUrl)`.
`handleConfig` in `Main` logs `git.token=<hidden>`.

**Security property**: token appears only in `.git/config` (local, never committed)
— never as a command-line argument, never in log files.

---

## DTR-050 — Daemon flag: one-shot vs. long-running process

**Milestone**: M7 | **Status**: Accepted

**Context**: CLI operations (`pull`, `push`, `sync`, `commit`) should terminate
automatically after completion when launched from a terminal. The Tray requires
the process to stay alive indefinitely.

**Decision**: `--daemon` flag in `Main`. Without it: after publishing the event,
`awaitIdle()` polls all per-vault queues every 250ms; on empty + 500ms settling
delay, calls `System.exit(0)`. With `--daemon`: blocks on `worker.join()` as
before. Early-exit operations (`status`, `config`) always exit immediately.

**Rationale**: single binary, two modes. Task Scheduler uses one-shot (no flag);
Tray uses `--daemon`.

---

## DTR-051 — `NomadSync status`: early-exit, output on System.out

**Milestone**: M7 | **Status**: Accepted

**Context**: `git status` output is a human-readable interactive response, not
a system event.

**Decision**: `status` is an early-exit operation — no orchestrators, no
scheduler. `GitService.status(Vault)` uses `runCommandWithLines` (preserves
newlines, unlike `runCommandWithOutput`). Output printed to `System.out`; errors
to `logService.error`. Without `--vault`: broadcasts across all vaults with
`=== repoSlug ===` headers.

---

## DTR-052 — `NomadProperties`/`NomadPropertiesLoader`: ForgeUI pattern applied

**Milestone**: M7 | **Status**: Accepted

**Context**: magic strings for property keys scattered across `Main`, `LogService`,
`GitService`, `VaultService`, `SocketClient`, `SocketServer`, `AutosaveScheduler`,
`SeqHttpLogWriter`.

**Decision**: `NomadProperties` (key registry, nested classes by domain: `Git`,
`Path`, `Log`, `Autosave`, `Commit`, `Socket`) and `NomadPropertiesLoader`
(classpath loader, `getBoolean`, `getEnum`) — identical pattern to ForgeUI
`ForgeProperties`/`ForgePropertiesLoader`. All magic strings eliminated.

---

## DTR-053 — macOS logoff strategy: launchd daemon + SIGTERM shutdown hook

**Milestone**: M8 | **Status**: Accepted for v1.0.0 — Option C deferred to v2.0.0

**Context**: macOS has no reliable, non-deprecated logoff trigger equivalent to
Windows Task Scheduler. Evaluated options:

- `LogoutHook` via `loginwindow` defaults — deprecated on Ventura+, requires sudo
- `launchd WatchPaths` on system paths — fragile, undocumented internal behaviour
- `NSWorkspaceWillPowerOffNotification` (Cocoa/Swift bridge) — correct native API,
  requires a separate native binary; deferred to v2.0.0

**Decision — v1.0.0 (Option B)**: NomadSync runs as a `launchd` user agent in
`--daemon` mode. Pull occurs at agent startup (`RunAtLoad = true` = logon). Push
occurs via the existing Java shutdown hook when macOS sends `SIGTERM` at
logout/shutdown — macOS guarantees approximately 20 seconds before forcing
termination, sufficient for a normal push.

```
Windows:  Task Scheduler logoff trigger → NomadSyncPush.bat → PUSH_LOGOFF
macOS:    launchd SIGTERM → Java shutdown hook → publish PUSH_LOGOFF → awaitIdle → exit
```

Java code is identical on both platforms. Only the external trigger mechanism differs.

**Rationale**: zero native code, zero Cocoa dependencies, reuses the existing
`--daemon` flag and shutdown hook infrastructure from M7 (DTR-050). The
20-second macOS timeout is sufficient for push under normal network conditions.

**Accepted risk**: on very slow networks, the push may not complete before macOS
forces termination. A warning is written to the log file — consultable at next
startup via `NomadSync doctor`, which reports whether the last push completed
successfully. Not a user-facing alert during shutdown.

**v2.0.0 — Option C**: `NSWorkspaceWillPowerOffNotification` via a Swift helper
binary — guaranteed pre-shutdown time, eliminates the timeout risk entirely.
Distribution under Belmani-Apex Apple Developer certificate (no Gatekeeper bypass
required for end users).

---

## DTR-054 — `NomadSync vault` subcommand: full CRUD from CLI

**Milestone**: M8 | **Status**: Accepted

**Context**: `vaults.json` must never be edited manually by the end user. M7
introduced `NomadSync config` for credential updates, but no CLI surface existed
for vault registration, modification, removal, or inspection.

**Decision**: `NomadSync vault <subcommand>` with five subcommands:
`add`, `update`, `remove`, `list`, `show`. Single dispatch entry in `Main`
(`operation == "vault"` → route on `args[1]`). All operations are early-exit.

`vault add` validates path existence, `.git/` presence, and repoSlug uniqueness
before calling `VaultService.create` + `GitService.bootstrapVault`.
`vault update` modifies non-credential fields of an existing vault (path, branch,
remote, name, owner) — credentials are managed by `NomadSync config`.
`vault remove` requires interactive confirmation — never deletes the local folder.
`vault list` shows repoSlug, path, branch, remote, and token presence (never value).
`vault show` includes the first 3 lines of `git status --short` with `...` if
more changes exist.

**Scripts**: one wrapper per subcommand (`NomadSyncVaultAdd.bat/.sh` etc.) for
BUBEZ discoverability, plus `NomadSyncVault.bat/.sh` as unified developer entry
point. Both coexist.

---

## DTR-055 — `NomadSync setup`: console wizard for first-run onboarding

**Milestone**: M8 | **Status**: Accepted

**Context**: the four-step manual installation process is inaccessible to
non-technical users.

**Decision**: `NomadSync setup` is a console wizard collecting Git identity,
token, and first vault in a guided sequence, then calling `handleConfig`,
`VaultService.create`, `GitService.bootstrapVault`, and `handleOsRegistration`
in order. Token input uses `System.console().readPassword()` — never echoed.
No pre-filled personal defaults in prompts.

`Main` detects first run by checking if `config.properties` is absent and
launches `setup` automatically before any other operation. jpackage produces a
native installer (`.exe` on Windows, `.pkg` on macOS) with a bundled JRE —
the user runs the installer, which places NomadSync in `Program Files` /
`/Applications`, and the first launch triggers the setup wizard automatically.

**Rationale**: single entry point for non-technical users; internally reuses all
existing M7 CLI infrastructure — no new persistence logic.

---

## DTR-056 — OS task registration from setup wizard

**Milestone**: M8 | **Status**: Accepted

**Context**: Task Scheduler (Windows) and launchd (macOS) registration requires
system commands that non-technical users cannot run manually.

**Decision**:
- **Windows**: `schtasks.exe /create` for `Pull at logon` and `Push at logoff`
  tasks, executed via `ProcessBuilder`. Requires admin elevation — jpackage
  installer already runs elevated.
- **macOS**: write `~/Library/LaunchAgents/io.aledep10.nomadsync.plist` and
  activate via `launchctl load`. No elevation required for user agents.

Both platforms: `setup` asks for confirmation before registering. Uninstaller
removes tasks via `schtasks /delete` (Windows) and `launchctl unload` + plist
deletion (macOS).

---

## DTR-057 — `NomadSync doctor`: health check with optional auto-repair

**Milestone**: M8 | **Status**: Accepted

**Context**: troubleshooting NomadSync requires opening log files and running
Git commands manually. On macOS, the push-at-shutdown result is not surfaced
interactively — the user needs a simple way to verify it at next startup.

**Decision**: `NomadSync doctor` verifies `config.properties`, `vaults.json`,
git executable, vault paths, remote reachability, token presence, OS task
registration, and last-shutdown push outcome (from log). Prints a structured
report to `System.out` with ✓/✗ per item. `--fix` attempts automatic repair of
detectable issues. Early-exit operation.

---

## DTR-058 — jpackage installer: native, bundled JRE, first-run auto-setup

**Milestone**: M8 | **Status**: Accepted

**Context**: requiring Java 21 to be pre-installed is a barrier for non-technical
users. A zip requiring manual extraction is not acceptable for a v1.0.0 release.

**Decision**: `jpackage` (JDK 21 built-in) produces `.exe` (Windows) and `.pkg`
(macOS) native installers with a bundled JRE. The user downloads from Softonic,
runs the installer (double-click), and NomadSync is placed in `Program Files` /
`/Applications`. On first launch, `Main` detects the absence of `config.properties`
and invokes `setup` automatically.

**macOS Gatekeeper**: v1.0.0 unsigned `.pkg` requires `xattr -cr NomadSync.pkg`
before installation — documented in README, acceptable for developer/early-adopter
distribution. v2.0.0: Belmani-Apex Apple Developer certificate — no bypass
required for end users, Gatekeeper-compliant distribution.