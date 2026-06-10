# ObsidianSync

A lightweight Java tool that keeps one or more Obsidian vaults in sync across multiple Windows machines using Git and GitHub — no subscription required.

ObsidianSync runs silently in the background as a system tray icon. It pulls the latest vault state at logon, commits local changes periodically, pushes at logoff, and lets you trigger a manual push with a single click. Multiple vaults are supported, each backed by its own private GitHub repository.

---

## Features

- **Automatic pull at logon** — the vault is always up to date when you start working
- **Periodic autosave** — local commits every 15 minutes, no network dependency
- **Automatic push at logoff** — changes are pushed to GitHub when the session ends
- **Manual push on demand** — left-click the tray icon to push the current vault at any time
- **Multi-vault support** — manage multiple vaults from the same tray icon, each mapped to its own repository
- **Vault switcher** — right-click the tray icon to switch the active vault; optionally push immediately on selection
- **Conflict resolution** — remote always wins on pull; every overwrite is traceable in the git log
- **Exponential backoff retry** — network failures are retried automatically (30s → 60s → 120s) before giving up
- **Zero cloud subscription** — uses a free private GitHub repository instead of Obsidian Sync (~€4/month)

---

## Requirements

- Java 11 or later
- Git installed and available on `PATH`
- A GitHub account with a private repository per vault
- Windows 10 or Windows 11

---

## Installation

1. Clone or download this repository
2. Copy `config.properties.template` to `config.properties` and fill in your settings
3. Copy `vaults.json.template` to `vaults.json` and configure your vaults
4. Build the fat JAR:
   ```
   mvn package
   ```
5. Register the three Task Scheduler tasks (see [Task Scheduler Setup](#task-scheduler-setup))
6. Start the tray manually once to verify, then let Task Scheduler handle it at every logon

---

## Configuration

### config.properties

```properties
socket.port=4242
log.path=logs/obsidian-sync.log
```

### vaults.json

```json
{
  "vaults": [
    {
      "id": "personal",
      "label": "Personal",
      "path": "C:/vaults/personal",
      "remote": "https://github.com/youruser/personal-vault",
      "token": "ghp_..."
    }
  ]
}
```

Add one entry per vault. Each vault must already be a git repository with the remote configured.

---

## Task Scheduler Setup

Three tasks must be registered in Windows Task Scheduler (`taskschd.msc`):

| Task name | Trigger | Action |
|---|---|---|
| `ObsidianSync-Tray` | At log on | `java -jar path\to\ObsidianSync.jar tray` |
| `ObsidianSync-Logon` | At log on (delay 30s) | `java -jar path\to\ObsidianSync.jar logon` |
| `ObsidianSync-Logoff` | At log off | `java -jar path\to\ObsidianSync.jar logoff` |

The autosave task is managed internally by the tray process and does not require a separate Task Scheduler entry.

All tasks should run under the current user account. The logoff task must be configured with "Run whether user is logged on or not" disabled and "Wait for task to complete" enabled.

---

## How it works

```
Logon
  └─ Tray process starts (hosts orchestrator + socket server on :4242)
  └─ Logon client connects → sends PULL_LOGON → orchestrator: stash → pull → stash pop

During session
  └─ Autosave every 15 min → orchestrator: diff → commit local (no network)
  └─ Left-click tray → PUSH_MANUAL → orchestrator: diff → commit → push

Logoff
  └─ Logoff client connects → sends PUSH_LOGOFF → orchestrator: diff → commit → push
  └─ Tray process exits
```

Events are queued with priority (pull > manual push > logoff push > autosave). If two events of the same type arrive, the latest replaces the earlier one in the queue. Network failures trigger up to three retry attempts with exponential backoff before the event is discarded and logged.

---

## Project structure

```
src/main/java/
├── Main.java
├── model/
│   ├── SyncEvent.java
│   └── Vault.java
├── service/
│   ├── GitService.java
│   └── LogService.java
├── orchestrator/
│   ├── SyncOrchestrator.java
│   └── SyncEventQueue.java
├── notification/
│   └── NotificationHook.java
├── socket/
│   ├── SocketServer.java
│   └── SocketClient.java
└── tray/
    └── TrayManager.java

src/main/resources/
├── config.properties.template
└── vaults.json.template
```

---

## Design decisions

All architectural decisions taken during development are recorded in the Decision Track Record, maintained in English inside `docs/`. Each milestone has its own DTR file:

- `docs/DTR_Milestone_1.md` — Git sync strategy, Task Scheduler, autosave
- `docs/DTR_Milestone_2.md` — SyncOrchestrator, event-driven architecture, retry policy
- `docs/DTR_Milestone_3.md` — Testing stack (JUnit 5, Mockito, AssertJ, Awaitility)
- `docs/DTR_Milestone_4.md` — Windows integration, multi-vault, tray icon, IPC

The DTR captures context, decision, rationale, and discarded alternatives for every non-trivial choice. It is the primary reference for understanding why the system is built the way it is.

---

## License

MIT