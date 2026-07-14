# NomadSync — Developer README

## Architecture overview

```
Main
 ├── VaultService          load vaults.json, CRUD, snapshots, conflicts
 ├── GitService            git CLI via ProcessBuilder, bootstrapVault
 ├── LogService            multi-writer fan-out, vault scoping
 │    ├── ConsoleLogWriter
 │    ├── FileLogWriter
 │    └── SeqHttpLogWriter (async, BlockingQueue + daemon thread, circuit breaker)
 ├── broadcast SyncEventQueue
 ├── broadcaster Thread    routes null-vaultId to all queues
 ├── AutosaveScheduler     periodic AUTOSAVE to broadcast queue
 └── per vault:
      ├── SyncEventQueue   priority queue, latest-wins dedup
      └── SyncOrchestrator worker thread, exponential backoff retry
```

---

## Key design decisions (summary — see ADR for full detail)

| ADR | Decision |
|-----|----------|
| NomadSync-EVT-001 | Event-driven, priority queue. Git is serial per repository. |
| NomadSync-EVT-003 | Latest-wins deduplication on same event type. |
| NomadSync-EVT-004 | Exponential backoff: 30s→60s→120s, max 3 retries. |
| NomadSync-GIT-004 | SYNCHRONIZE: `-X ours`, FIFO backup, remote-conflicts. |
| NomadSync-VLT-001 | `repoSlug = owner/name` as universal vault identifier. |
| NomadSync-VLT-002 | Uniqueness on `repoSlug` AND `path`, not on `name` alone. |
| NomadSync-VLT-003 | Exception hierarchy: `VaultParseException` / `VaultIntegrityException`. |
| NomadSync-EVT-008 | Broadcast queue: `vaultId=null` fans out to all per-vault queues. |
| NomadSync-EVT-012 | `mandatoryVault` flag on `EventType`: broadcast vs. error. |
| NomadSync-GIT-010 | Token never logged: `CommandUtil.sensitiveArgs` masking. |
| NomadSync-EVT-013 | `--daemon` flag: one-shot (default) vs. long-running (Tray). |

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
 │    ├── VaultException.java
 │    ├── VaultParseException.java     file absent/unreadable → recoverable
 │    └── VaultIntegrityException.java duplicate repoSlug/path → non-recoverable
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
 │    ├── GitService.java              bootstrapVault, status(), statusShort()
 │    ├── GitignoreService.java
 │    ├── LogService.java
 │    └── VaultService.java            create/update/delete/load, loadVaults()
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
- **Vault construction in tests**: use `new Vault(uuid, owner, name, path)`,
  never mutate the live instance returned by `create()` — it is the same reference
  stored in the internal `HashMap`.
- **Random order**: `-DrunOrder=random` via Maven Surefire. Each test must be
  independent — no shared mutable state between tests.
- **Package-private constructors**: `SyncEvent(type, vaultId, message, timestamp,
  retryDelay)` and `AutosaveScheduler(queue, log, interval, TimeUnit)` for
  deterministic test scenarios.
- **Reflection**: private static handler methods in `Main` are tested via
  `Main.class.getDeclaredMethod(...)` + `setAccessible(true)`. `InvocationTargetException`
  wraps the real cause — unwrap with `getCause().getCause()` when debugging.

---

## CLI reference

```
java -jar NomadSync.jar <operation> [subcommand] [flags...]

Operations:
  pull     Pull from remote (broadcast if --vault absent)
  push     Push to remote   (broadcast if --vault absent)
  sync     Full bidirectional sync (broadcast if --vault absent)
  status   git status output (broadcast if --vault absent)
  commit   Local commit with editor message (--vault required)
  autosave Periodic — managed by AutosaveScheduler, not for manual use
  config   Update config.properties or vaults.json (early-exit)
  vault    Vault registry management (early-exit)
    add      Register a new vault (--owner, --name, --path required)
    update   Update an existing vault (--vault required)
    remove   Remove a vault with interactive confirmation (--vault required)
    list     List all registered vaults
    show     Show vault details and live git status (--vault required)

Flags:
  --config=<path>           config.properties path (default: ./config.properties)
  --vault=<name|owner/name> target vault (absent = broadcast for non-mandatory ops)
  --daemon                  keep process alive (Tray mode)
  --editor=<path>           editor for commit message
  --git.<key>=<value>       credential/config flags for 'config' and 'vault update'
```

---

## Vault subcommand dispatch

`vault` subcommands are positional — `args[1]` is extracted before flag parsing:

```
NomadSync vault add --owner=X --name=Y --path=Z
                ↑
            args[1] → flags.put("sub", "add")
            args[2..n] → parsed as --key=value flags
```

Scripts `NomadSyncVault.bat` and `NomadSyncVaultAdd.bat` etc. wrap this pattern
for BUBEZ discoverability.

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
- Written to the vault's local `.git/config` via `git remote set-url` or `git remote add`
- Never passed as a command-line argument to any Git command
- Never written to any log file (masked as `<hidden>` via `CommandUtil.sensitiveArgs`)
- Never committed (`.git/` is excluded from tracking by Git design)
- `config.properties` itself is in `.gitignore` — never committed to the repository

---

## Exception handling

```
NomadSyncException (base)
 ├── GitException          Git CLI error or unexpected process failure
 ├── NetworkException      connectivity failure (matched via NETWORK_PATTERNS)
 └── VaultException        vault lifecycle error
      ├── VaultParseException       file absent/corrupted → recover with empty state
      └── VaultIntegrityException   duplicate repoSlug or path → System.exit(1)
```

`loadVaults()` in `Main` catches the two subclasses before the base —
`VaultParseException` continues with empty registry; `VaultIntegrityException`
terminates. The unreachable `return new ArrayList<>()` at the end satisfies
the compiler's definite assignment check.

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