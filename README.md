# ObsidianSync

> Automated Git-based synchronization of an Obsidian vault across multiple Windows machines.
> Pull on logon, push on logoff, differential autosave in between — fully unattended.

---

## The problem

Obsidian stores notes as plain markdown files on disk. Cloud sync tools like OneDrive modify
files asynchronously and concurrently, causing corruption and conflicts while Obsidian is open.

ObsidianSync solves this by replacing cloud sync with **atomic Git operations** — pull before
you open, push after you close, autosave every N minutes in between. No external process ever
touches your files while Obsidian is running.

---

## Features

- **Logon pull** — fetches the latest vault version from remote before the session starts
- **Logoff push** — commits and pushes all changes when the session ends
- **Differential autosave** — commits locally only when `git diff` detects actual changes
- **Conflict resolution** — `git pull -X theirs` with full audit logging; remote is source of truth
- **Exponential backoff retry** — up to 3 retries on network failure (30s → 60s → 120s)
- **Event-driven orchestration** — prioritized queue prevents Git concurrency issues
- **Multi-vault support** — run multiple instances with separate config files
- **Notification hook** — extensible interface for user-facing alerts (default: log file)
- **Task Scheduler integration** — triggers on Windows logon/logoff events, no admin rights required
- **Fat JAR deployment** — single artifact, copy `target/` and run

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                        Main                         │
│         parses args, loads config, publishes        │
│              PULL_LOGON / PUSH_LOGOFF               │
└──────────────────────┬──────────────────────────────┘
                       │ publishes SyncEvent
┌──────────────────────▼──────────────────────────────┐
│                  SyncEventQueue                     │
│    priority queue · latest-wins deduplication       │
│         PULL(1) > PUSH_MANUAL(2) >                  │
│              PUSH_LOGOFF(3) > AUTOSAVE(4)           │
└──────────────────────┬──────────────────────────────┘
                       │ consumes serially
┌──────────────────────▼──────────────────────────────┐
│                 SyncOrchestrator                    │
│   worker loop · switch on EventType · retry with   │
│            exponential backoff · NotificationHook  │
└──────────────────────┬──────────────────────────────┘
                       │ executes single git commands
┌──────────────────────▼──────────────────────────────┐
│                   GitService                        │
│   add · commit · push · pull · stash · diff        │
│       commitLocal() separated from push()          │
└─────────────────────────────────────────────────────┘
```

**Supporting classes**: `LogService` (levelled, thread-safe, append-only),
`AutosaveScheduler` (ScheduledExecutorService, publishes AUTOSAVE events),
`NotificationHook` (interface — default implementation writes to log).

---

## Tech stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Build | Maven · maven-assembly-plugin (fat JAR) |
| Git integration | ProcessBuilder (CLI wrapping) |
| Scheduling | ScheduledExecutorService |
| Concurrency | PriorityBlockingQueue · synchronized |
| OS integration | Windows Task Scheduler · Batch scripts |
| Logging | Custom LogService (slf4j-ready) |
| Testing | JUnit 5 |

---

## Project structure

```
ObsidianSync/
├── pom.xml
├── ObsidianSync.bat              ← generic launcher (pull / push / autosave)
├── ObsidianSyncPush.bat          ← one-click push shortcut for taskbar
├── config.properties.template   ← committed reference — copy and fill credentials
│
└── src/main/java/io/aledep10/obsidiansync/
    ├── Main.java
    ├── hook/
    │   └── NotificationHook.java
    ├── orchestrator/
    │   ├── EventType.java
    │   ├── SyncEvent.java
    │   ├── SyncEventQueue.java
    │   └── SyncOrchestrator.java
    ├── scheduler/
    │   └── AutosaveScheduler.java
    └── service/
        ├── GitService.java
        └── LogService.java
```

---

## Getting started

### Prerequisites

- Java 21+
- Git installed and available on PATH (or set `git.executable` to the full path)
- A GitHub repository for your vault

### Setup

**1. Clone or download**
```bash
git clone https://github.com/AleDeP10/ObsidianSync.git
cd ObsidianSync
```

**2. Build**
```bash
mvn package
```

**3. Configure**
```bash
cp target/config.properties.template target/config.properties
# edit config.properties with your vault path, repo URL and GitHub token
```

**4. Run manually**
```bash
cd target
ObsidianSync.bat pull
ObsidianSync.bat push
ObsidianSync.bat autosave
```

### Task Scheduler setup

Create three tasks in `taskschd.msc`:

| Task | Trigger | Action |
|---|---|---|
| ObsidianSync-Pull | At logon | `ObsidianSync.bat pull` |
| ObsidianSync-Push | At logoff | `ObsidianSync.bat push` |
| ObsidianSync-Autosave | Every 15 min (while logged on) | `ObsidianSync.bat autosave` |

---

## Configuration reference

```properties
# Environment
env=prod

# Paths
vault.path=C:/Users/YourUser/Obsidian/YourVault

# Git
git.executable=C:/Program Files/Git/bin/git.exe
git.remote=origin
git.branch=main
git.username=your-github-username
git.token=ghp_yourPersonalAccessTokenHere

# Autosave
autosave.interval.minutes=15

# Logging
log.path=C:/Users/YourUser/ObsidianSync/logs/obsidiansync.log
log.level=INFO
```

> ⚠️ `config.properties` is excluded from version control. Never commit your GitHub token.

---

## Design decisions

All architectural decisions are documented ADR-style in the companion vault:

👉 [obsidian-portfolio — ObsidianSync Diary](https://github.com/AleDeP10/obsidian-portfolio)

Key decisions include: Git over OneDrive, `theirs` conflict strategy, event-driven
orchestration over direct calls, `commitLocal()` separated from `push()`, and
dependency inversion for the notification layer.

---

## Roadmap

- [ ] System tray icon with manual push button
- [ ] Multi-vault configuration (single config, multiple vault profiles)
- [ ] Toast notifications via `NotificationHook`
- [ ] Unit test coverage for `GitService` and `SyncOrchestrator`
- [ ] GitHub Actions CI pipeline

---

## About

Built by **Alessandro De Prato**, Full Stack Developer.

This project exists both as a practical tool and as a learning exercise in
Java, concurrency, process automation, and Agile delivery — documented end-to-end.

[GitHub](https://github.com/AleDeP10) · [Portfolio](https://aledep10.github.io) · [Portfolio Vault](https://github.com/AleDeP10/obsidian-portfolio) · [LinkedIn](https://www.linkedin.com/in/alessandro-de-prato)
