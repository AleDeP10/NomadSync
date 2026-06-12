# Decision Track Record — Milestone 1

## [M1] Git-based sync instead of OneDrive

**Context**: the Obsidian vault needs to stay in sync across multiple Windows machines.
OneDrive is incompatible with Obsidian due to asynchronous and concurrent file modifications.

**Decision**: Git + GitHub

**Motivation**: pull/push operations are atomic and synchronous; native diff enables differential
autosave; no external process touches the files while Obsidian is open.

**Discarded alternatives**:
- OneDrive direct sync: causes conflicts due to concurrent writes
- Syncthing: P2P, requires at least one device to always be on
- Obsidian Sync: official solution but paid (~$4/month)

---

## [M1] `theirs` conflict resolution strategy on pull

**Context**: the pull runs automatically at logon without user supervision. In case of a Git
conflict, the process would block waiting for manual intervention.

**Decision**: `git pull -X theirs` — on conflict, the remote version always wins.

**Motivation**: the remote repository represents the last version consciously saved via logoff.
It is the source of truth. Every overwrite is tracked in the log.

**Accepted risks**: uncommitted local changes could be lost in case of a conflict before push.
Mitigated by periodic autosave and `git stash` before pull.

---

## [M1] Logon sequence: stash → pull → stash pop

**Context**: if uncommitted local changes are present at logon, `git pull` refuses to proceed.

**Decision**: run `git stash` before pull and `git stash pop` after.

**Motivation**: preserves local changes during pull without requiring an explicit commit.
Transparent behaviour for the user.

**Discarded alternative**: `git reset --hard` before pull — destroys local changes with no
recovery option.

---

## [M1] Logon/logoff hook via Task Scheduler instead of gpedit.msc

**Context**: scripts must run automatically at Windows logon and logoff.

**Decision**: Task Scheduler (`taskschd.msc`)

**Motivation**: available on all Windows editions including Home; inspectable UI; execution
history; XML task export for reproducibility across machines.

**Discarded alternative**: Group Policy Editor (`gpedit.msc`) — not available on Windows Home;
less flexible for the periodic timer.

---

## [M1] Differential autosave via `git diff --quiet`

**Context**: the scheduled autosave must not generate empty commits when no file changes
are detected.

**Decision**: use `git diff --quiet` as a guard — exit code 0 means no changes,
exit code 1 means changes are present.

**Motivation**: native Git, zero additional dependencies, clear semantics via exit code.

---

## [M1] Fat JAR via `maven-assembly-plugin`

**Context**: the JAR must be executable standalone from Task Scheduler and the command line,
without requiring external classpaths.

**Decision**: `maven-assembly-plugin` with `jar-with-dependencies` descriptor.

**Motivation**: produces a single self-contained artifact; simplifies deployment across
machines — copying the `target/` folder is sufficient.

---

## [M1] Resources copied to `target/` via `maven-resources-plugin`

**Context**: `NomadSync.bat` and `config.properties` must be placed alongside the JAR
to be resolved via relative paths.

**Decision**: `maven-resources-plugin` copies files from `src/main/resources/` to `target/`
during the `package` phase.

**Motivation**: the deployment structure is self-contained in `target/`; no manual
post-build configuration needed.

---

## [M1] Two separate configuration files per environment

**Context**: the properties file contains Git credentials (GitHub token) that must not
be committed to version control.

**Decision**: `config.dev.properties` and `config.prod.properties` excluded via `.gitignore`;
`config.properties.template` committed as a reference.

**Motivation**: clean separation between configuration and code; credential security;
simplified onboarding via the template file.

---

## [M1] Thread-safe logging via `synchronized`

**Context**: `AutosaveScheduler` runs on a separate thread and could write to the log
concurrently with the main thread.

**Decision**: `LogService.log()` declared `synchronized`.

**Motivation**: simple and correct solution for the expected concurrency level (two threads
at most). Negligible overhead for file I/O operations.

**Future alternatives**: `ReentrantLock` or `BlockingQueue` with a dedicated writer thread,
should concurrency increase.

---

## [M1] `SyncOrchestrator` as intermediate layer between `io.nomadsync.io.nomadsync.Main` and `GitService`

**Context**: the logic for coordinating operations (e.g. stash before pull, exit code
handling) belongs neither to `io.nomadsync.io.nomadsync.Main` nor to `GitService`.

**Decision**: introduce `SyncOrchestrator` as a dedicated layer. `io.nomadsync.io.nomadsync.Main` calls the
orchestrator; `GitService` executes only individual Git commands.

**Motivation**: separation of concerns; `GitService` remains independently testable;
business logic is centralised and not duplicated.

**Status**: scaffolding completed, implementation planned for Milestone 2.
