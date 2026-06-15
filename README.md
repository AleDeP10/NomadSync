# NomadSync

A lightweight, cross-platform Java tool that keeps one or more folders in sync
across multiple machines using Git and GitHub — no subscription required.

NomadSync has no dependency on any specific application: any folder backed by
a private Git repository — notes, configuration, projects, dotfiles, or vaults
from tools like Obsidian, Logseq, or similar — can be managed the same way. It
runs as a background process, optionally with a system tray icon, and
supports multiple independent folders ("vaults"), each mapped to its own
GitHub repository and, optionally, its own owner and credentials.

---

## Features

- **Automatic pull at logon** — every vault is up to date before you start working
- **Periodic autosave** — local commits at a configurable interval, no network dependency
- **Automatic push at logoff** — local changes are pushed to GitHub when the session ends
- **One-shot synchronize** — pull, resolve, and push in a single operation, with automatic conflict handling
- **Multi-vault support** — manage any number of folders from a single process, each backed by its own repository and (optionally) its own GitHub account
- **Broadcast and targeted operations** — synchronize a single vault or all registered vaults at once
- **Conflict resolution with safety net** — on divergence, your local version is kept (`-X ours`); the remote version and a full pre-conflict snapshot are saved for manual review, never silently discarded
- **FIFO backups** — the last 3 pre-conflict snapshots per vault are retained automatically
- **Exponential backoff retry** — network failures are retried automatically (30s → 60s → 120s) before giving up, with notification on final failure
- **Structured logging** — console, file, and optional [Seq](https://datalust.co/seq) structured log server, all vault-tagged for multi-vault observability
- **Zero cloud subscription** — uses a free private GitHub repository per vault instead of paid sync services

---

## Requirements

- Java 21 or later
- Git, available on `PATH` (or configured explicitly via `git.executable`)
- A GitHub account with a private repository per vault
- Windows, macOS, or Linux

---

## Installation

1. Clone or download this repository
2. Copy `config.properties.template` to `config.properties` and fill in your settings
3. Copy `vaults.json.template` to `vaults.json` and register your vaults
4. Build the fat JAR:

   ```
   mvn package
   ```

5. Copy the contents of `target/` to a dedicated folder (e.g. `~/Tools/NomadSync`)
6. Run a manual `pull` once to verify your configuration, then register the
   scheduled tasks for your platform (Windows Task Scheduler, macOS
   `launchd`, or Linux `cron`/`systemd`)

---

## Configuration

### config.properties

```properties
# -- Paths --------------------------------------------------
path.vaults=./vaults.json
path.backup=./backup
path.conflicts=./remote_conflicts

# -- Git ------------------------------------------------------
git.executable=git
git.remote=origin
git.branch=main
git.name=Your Name
git.email=you@example.com
git.username=your-github-username
git.token=ghp_...

# -- Autosave ---------------------------------------------------
autosave.interval.minutes=15

# -- Logging ----------------------------------------------------
log.writers=console,file,seq
log.path=logs/nomadsync.log
log.level=INFO
log.seq.url=http://localhost:5341
```

`git.executable=git` resolves via `PATH` on Windows, macOS, and Linux. Override
with an absolute path only if Git is not on `PATH` (e.g.
`C:/Program Files/Git/bin/git.exe`).

`log.writers` accepts any combination of `console`, `file`, `seq`
(comma-separated). The `seq` writer ships structured, vault-tagged events to a
[Seq](https://datalust.co/seq) server — useful for observing multiple vaults
in real time. If `seq` is configured but unreachable, NomadSync continues
normally; events are simply dropped.

### vaults.json

```json
{
  "vaults": [
    {
      "id":    "A768-6CF3-10B-0000",
      "owner": "your-github-username",
      "name":  "personal-vault",
      "path":  "/path/to/your/vault"
    }
  ]
}
```

Add one entry per vault. Each `path` must already be a Git repository with its
remote configured. `name` must be unique across all registered vaults — it is
used to name backup and conflict directories
(`backup/<name>_<timestamp>/`, `remote_conflicts/<name>_<timestamp>/`), and a
duplicate will be rejected at load time.

Optional per-vault fields `gitName`, `gitEmail`, `gitUsername`, `gitToken`
allow overriding global Git credentials for a specific vault — useful when a
vault belongs to a different GitHub account than your default.

---

## Usage

NomadSync is a single executable invoked with an operation and a config file:

```
java -jar NomadSync.jar <pull|push|sync|autosave> config.properties [vaultId]
```

`vaultId` is optional. For `pull`, `push`, and `sync` it scopes the operation
to a single vault — if omitted, `pull`/`push` default to the first registered
vault, and `sync` broadcasts to **all** vaults. `autosave` always broadcasts
and ignores `vaultId`.

### Command-line shortcuts

For day-to-day use, a set of platform scripts (`.bat` on Windows, `.sh` on
macOS/Linux) wrap the most common operations — one per event type, each
calling the shared dispatcher script with the right arguments:

| Script | Operation | Effect | Typical use |
|---|---|---|---|
| `NomadSync.bat`/`.sh <op> [config] [vaultId]` | dispatcher | any operation | scripting, Task Scheduler/cron entries |
| `NomadSyncPull.bat`/`.sh` | `pull` | stash → pull → stash pop | start of session |
| `NomadSyncPush.bat`/`.sh` | `push` | commit local changes → push | end of session |
| `NomadSyncSync.bat`/`.sh` | `sync` | commit → pull (with conflict handling) → push | on-demand, single click |
| `NomadSyncCommit.bat`/`.sh` | commit | opens your default text editor for a commit message, commits locally — no push | checkpoint work-in-progress with a meaningful message |

These shortcuts give developers the same fine-grained control over Git
operations that the tray icon gives end users — without remembering CLI
syntax. `NomadSyncCommit` in particular is a developer-facing addition: it
lets you create a clean, meaningful local commit at any point, without
triggering autosave's generic timestamped message or a full synchronize cycle.

There is intentionally no `NomadSyncAutosave` script — autosave is managed
internally by the running process on its configured interval and is not
meant to be triggered manually.

---

## How it works

```
Logon
  └─ pull → stash (if dirty) → git pull → stash pop

During session
  └─ autosave every N minutes → commit local only (no network)
  └─ on-demand sync → commit local → pull
       ├─ no conflict → push
       └─ conflict → backup snapshot → pull -X ours → save remote version
                       for review → push (local version preserved)

Logoff
  └─ push → commit local (if dirty) → push
```

Events are processed serially per vault through a priority queue — pull takes
precedence over synchronize, which takes precedence over logoff push, which
takes precedence over autosave. If two events of the same type are queued for
the same vault, the most recent replaces the earlier one. Network failures
trigger up to three retry attempts with exponential backoff (30s → 60s → 120s)
before the event is discarded and a failure notification is raised.

`autosave` and broadcast `sync` reach **all** registered vaults through a
dedicated broadcast queue, regardless of how many vaults are configured —
adding a vault to `vaults.json` requires no changes to scheduled tasks.

---

## Project structure

```
src/main/java/io/aledep10/nomadsync/
├── Main.java
├── dto/                  ← Jackson-annotated DTOs, isolated from domain classes
├── orchestrator/
│   ├── Vault.java
│   ├── VaultContext.java
│   ├── SyncEvent.java
│   ├── SyncEventQueue.java
│   └── SyncOrchestrator.java
├── scheduler/
│   └── AutosaveScheduler.java
├── service/
│   ├── GitService.java
│   ├── GitignoreService.java
│   ├── VaultService.java
│   └── LogService.java
├── logging/
│   ├── ConsoleLogWriter.java
│   ├── FileLogWriter.java
│   └── SeqHttpLogWriter.java
├── hook/
│   └── NotificationHook.java
└── util/
    ├── CommandUtil.java
    └── JsonMapper.java

src/main/resources/
├── config.properties.template
└── vaults.json.template
```

---

## Design decisions

All architectural decisions taken during development are recorded in the
unified [Decision Track Record](docs/DTR.md) (`docs/DTR.md`), available in
[English](docs/DTR.md) and [Italian](docs/DTR_it.md). Each entry captures
context, decision, rationale, and evaluated alternatives — it is the primary
reference for understanding why the system is built the way it is.

---

## Roadmap

NomadSync is the first of a planned family of cross-platform desktop tools
sharing a common Java/JavaFX foundation, including a shared design system
([ForgeUI](docs/DTR.md#dtr-044--forgeui--shared-javafx-design-system-maven-central-candidate)),
a system tray UI, a JavaFX dashboard (`MainWindow`), and internationalisation
across 10 languages. See `docs/DTR.md` for the current status of each
planned component.

---

## Authors

**Alessandro De Prato** · Senior Software Engineer – Product Owner, Tech Lead
[Portfolio](https://aledep10.github.io/) · [GitHub](https://github.com/AleDeP10) · [LinkedIn](https://www.linkedin.com/in/alessandro-de-prato)

**Gabriela Belmani** · Software Engineer – Developer, QA Engineer
[GitHub](https://github.com/Belmani) · [LinkedIn](https://www.linkedin.com/in/gabriela-da-sa%C3%BAde-belmani-tumfart)

---

## License

MIT