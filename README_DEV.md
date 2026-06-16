# NomadSync — Developer README

## Architecture overview

```
Main
 ├── VaultService          load vaults.json, CRUD, snapshots, conflicts
 ├── GitService            git CLI via ProcessBuilder, bootstrapVault
 ├── LogService            multi-writer fan-out, vault scoping
 │    ├── ConsoleLogWriter
 │    ├── FileLogWriter
 │    └── SeqHttpLogWriter (async, BlockingQueue + daemon thread)
 ├── broadcast SyncEventQueue
 ├── broadcaster Thread    routes null-vaultId to all queues
 ├── AutosaveScheduler     periodic AUTOSAVE to broadcast queue
 └── per vault:
      ├── SyncEventQueue   priority queue, latest-wins dedup
      └── SyncOrchestrator worker thread, exponential backoff retry
```

---

## Key design decisions (summary — see DTR for full detail)

| DTR | Decision |
|-----|----------|
| DTR-011 | Event-driven, priority queue. Git is serial per repository. |
| DTR-014 | Latest-wins deduplication on same event type. |
| DTR-015 | Exponential backoff: 30s→60s→120s, max 3 retries. |
| DTR-026 | SYNCHRONIZE: `-X ours`, FIFO backup, remote-conflicts. |
| DTR-031 | `repoSlug = owner/name` as universal vault identifier. |
| DTR-039 | Broadcast queue: `vaultId=null` fans out to all per-vault queues. |
| DTR-046 | Uniqueness on `repoSlug` AND `path`, not on `name` alone. |
| DTR-048 | `mandatoryVault` flag on `EventType`: broadcast vs. error. |
| DTR-049 | Token never logged: `CommandUtil.sensitiveArgs` masking. |
| DTR-050 | `--daemon` flag: one-shot (default) vs. long-running (Tray). |

---

## Package structure

```
io.aledep10.nomadsync
 ├── Main.java
 ├── config/
 │    ├── NomadProperties.java         key registry
 │    └── NomadPropertiesLoader.java   classpath loader
 ├── dto/
 │    ├── VaultDto.java
 │    └── VaultRootDto.java
 ├── exception/
 │    ├── GitException.java
 │    ├── NetworkException.java
 │    ├── NomadSyncException.java
 │    └── VaultException.java
 ├── gitignore/
 ├── hook/
 │    ├── NotificationHook.java
 │    └── LogNotificationHook.java
 ├── logging/
 │    ├── LogLevel.java
 │    ├── LogWriter.java
 │    ├── LogFormatter.java
 │    ├── LineFormatter.java
 │    ├── ClefFormatter.java
 │    ├── ConsoleLogWriter.java
 │    ├── FileLogWriter.java
 │    ├── InMemoryLogWriter.java
 │    └── SeqHttpLogWriter.java
 ├── orchestrator/
 │    ├── Vault.java
 │    ├── VaultContext.java            record
 │    ├── EventType.java               mandatoryVault, priority
 │    ├── SyncEvent.java               message field, forVault()
 │    ├── SyncEventQueue.java          priority, dedup, isEmpty()
 │    └── SyncOrchestrator.java        worker thread, retry
 ├── scheduler/
 │    └── AutosaveScheduler.java
 ├── service/
 │    ├── GitService.java              bootstrapVault, status()
 │    ├── GitignoreService.java
 │    ├── LogService.java
 │    └── VaultService.java
 ├── tray/
 │    ├── SocketServer.java
 │    ├── SocketClient.java
 │    └── ...
 └── util/
      ├── CommandUtil.java             sensitiveArgs masking, runCommandWithLines
      ├── DateFormats.java
      ├── JsonMapper.java
      ├── Os.java
      ├── OsUtil.java
      ├── StringUtil.java              coalesce()
      └── ValidationUtil.java
```

---

## Build

```bash
mvn clean package          # compile + test + fat JAR
mvn test                   # tests only
mvn test -DrunOrder=random # random order (regression detection)
```

Output: `target/NomadSync-1.0-SNAPSHOT-jar-with-dependencies.jar`

---

## Test conventions

- **Unit tests**: mock `GitService`, `NotificationHook`. Real `SyncEventQueue`.
- **Integration tests** (`GitServiceTest`): real Git repository in temp directory.
  `@BeforeEach` runs `git init` + `git config user.*`; `@AfterEach` deletes.
- **Vault construction in tests**: use `new Vault(uuid, owner, name, path)` copy,
  never mutate the live instance returned by `create()` — it is the same reference
  stored in the internal `HashMap`.
- **Random order**: `-DrunOrder=random` via Maven Surefire. Each test must be
  independent — no shared mutable state between tests.
- **Package-private constructors**: `SyncEvent(type, vaultId, message, timestamp,
  retryDelay)` and `AutosaveScheduler(queue, log, interval, TimeUnit)` for
  deterministic test scenarios.

---

## CLI reference

```
java -jar NomadSync.jar <operation> [flags...]

Operations:
  pull     Pull from remote (broadcast if --vault absent)
  push     Push to remote   (broadcast if --vault absent)
  sync     Full bidirectional sync (broadcast if --vault absent)
  status   git status output to stdout (broadcast if --vault absent)
  commit   Local commit with editor message (--vault required)
  autosave Periodic — managed by AutosaveScheduler, not for manual use
  config   Update config.properties or vaults.json (early-exit)

Flags:
  --config=<path>           config.properties path (default: ./config.properties)
  --vault=<name|owner/name> target vault (absent = broadcast for non-mandatory ops)
  --daemon                  keep process alive (Tray mode)
  --editor=<path>           editor for commit message
  --git.<key>=<value>       credential/config flags for 'config' operation
```

---

## Adding a new EventType

1. Add constant to `EventType` with priority and `mandatoryVault` value
2. Add `case` in `SyncOrchestrator.execute()`
3. Add `case` in `Main.main()` dispatch switch
4. Add `case` in `Main.operationToEventType()`
5. Add script `NomadSync<Operation>.bat/.sh`
6. Add tests in `SyncOrchestratorTest`

---

## Token security

The GitHub token is:
- Stored in the vault's local `.git/config` via `git remote set-url`
- Never passed as a command-line argument to any Git command
- Never written to any log (masked as `<hidden>` via `CommandUtil.sensitiveArgs`)
- Never committed (`.git/` is excluded from tracking by Git design)
- Never stored in `config.properties` in plaintext on disk after `NomadSyncConfig`
  calls `Properties.store()` — but `config.properties` itself is in `.gitignore`

---

## Branching strategy

Git Flow AVH Edition (bundled with Git for Windows):

```
main      → tagged releases only
develop   → continuous sprint integration
feature/* → one branch per grooming objective
release/* → version bump + final fixes
hotfix/*  → urgent fixes on main, merged to develop
```
