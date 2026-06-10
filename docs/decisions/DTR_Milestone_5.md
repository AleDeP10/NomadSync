# DTR — Milestone 5 & 6 (English)

---

## [M5] SYNCHRONIZE replaces PULL_MANUAL and PUSH_MANUAL

**Context**: `PULL_MANUAL` and `PUSH_MANUAL` were separate events. Every manual sync
requires both pulling remote changes and pushing local ones. Separate events force the
user to think in Git terms and expose them to rejected non-fast-forward errors.

**Decision**: single `SYNCHRONIZE` event replaces both.

| Priority | Event |
|---|---|
| 1 | `PULL_LOGON` |
| 2 | `SYNCHRONIZE` |
| 3 | `PUSH_LOGOFF` |
| 4 | `AUTOSAVE` |

**Motivation**: one event, one mental model. Non-fast-forward errors absorbed internally.

---

## [M5] SYNCHRONIZE conflict strategy — -X ours with FIFO backup

**Context**: `-X theirs` overwrites local work — inappropriate during an active session.
Strategy verified through direct field experimentation.

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
│       │       → remote-conflicts/<vault>_{timestamp}/<file>
│       ├─ git push
│       └─ return conflicted file list to caller
```

**Field-verified constraints**:
- `git merge --abort` non-zero if no merge active — ignore correctly
- `-X ours` requires local changes committed before execution
- Use `FETCH_HEAD`, not `MERGE_HEAD` — does not exist post-merge
- `--no-pager` mandatory on `git show` — prevents opening `less`
- `--no-edit` mandatory on `git pull -X ours` — prevents opening commit editor

**Motivation**: `-X ours` preserves local work by design. Backup is a silent safety net.
User notified only when divergence is real.

---

## [M5] FIFO backup — maximum 3 snapshots per vault

**Context**: conflict resolution creates full vault snapshots. Without retention, they
accumulate indefinitely.

**Decision**: max 3 per vault. FIFO — oldest deleted before new one when limit reached.
Path: `backups/<vault-name>_{timestamp}/`. Format `YYYY-MM-DD_HH-mm` — human-readable,
sorts chronologically in Explorer.

---

## [M5] TrayIcon — four visual states

**Context**: user needs passive sync status feedback without opening any window.

**Decision**: four states via `trayIcon.setImage()` with pre-loaded images:
- **Idle** — static green
- **Syncing** — animated
- **Error** — red: last sync failed after 3 retries
- **Conflict** — orange: files in `remote-conflicts/` awaiting review

Left-click → `SYNCHRONIZE` on current vault. Right-click → `ContextMenu`.
Hover → tooltip "Last sync: X minutes ago".

**Motivation**: consistent with Dropbox / OneDrive / Docker tray conventions.

---

## [M5] ContextMenu — zero cognitive decisions

**Context**: users need fast access to sync operations without opening MainWindow
and without knowing Git concepts.

**Decision**: `PopupMenu` AWT with four grouped sections:

```
● <current vault>  ▶   → VaultSwitcherPanel
─────────────────────
Sync current vault      → SYNCHRONIZE on current vault
Sync all vaults         → SYNCHRONIZE broadcast
Pull current vault      → PULL_LOGON on current vault
─────────────────────
Last sync: X min ago    (non-clickable label)
View log                → MainWindow tab Log (M6)
─────────────────────
Open Dashboard          → MainWindow (M6)
Open vault folder       → Desktop.getDesktop().open(vault.path)
─────────────────────
Exit
```

**Motivation**: every label describes exactly what it does — the user does not need to
know what a pull or push is.

---

## [M5] VaultSwitcherPanel — highest-responsibility component in the quick menu

**Context**: vault switching must be accessible without opening MainWindow.
Save on selection eliminates a manual sync step after switching.

**Decision**: AWT `Menu` nested as submenu on the first ContextMenu entry.
`CheckboxMenuItem` per vault — current vault has a checkmark. "Save on selection"
`CheckboxMenuItem` fixed at the bottom, outside scroll area.

On selection: update `current-vault.json`, update `TrayIcon` tooltip,
and if "Save on selection" active — publish `SYNCHRONIZE` on the selected vault.

**Motivation**: `VaultSwitcherPanel` has the most side effects of any quick-menu
component — documenting them explicitly prevents implementation surprises.

---

## [M5] ToastNotification — three scenarios, two implementation strategies

**Context**: sync completes asynchronously. Conflict resolution requires an actionable
notification with direct folder access.

**Decision**:
- **Success** — AWT native `trayIcon.displayMessage()`, auto-dismiss.
- **Conflict resolved** — `JDialog` persistent:
    - "Apri versioni remote" → `Desktop.getDesktop().open(remote-conflicts/<vault>_{timestamp}/)`
      — opens the most recent snapshot, not the root
    - "Apri backup locale" → `Desktop.getDesktop().open(backups/<vault>_{timestamp}/)`
- **Network failure** — `JDialog` persistent, priority-1 events only.

`Desktop.getDesktop().open(File)` is the correct Java API for native folder opening —
zero dependencies, works on Windows (Explorer) and macOS (Finder).

**Motivation**: notification weight matched to severity.

---

## [M5] VaultService — vault name uniqueness constraint

**Context**: backup and remote-conflicts folders use `vault.name`. Duplicates create
ambiguous folders during manual conflict resolution.

**Decision**: `VaultService.create()` and `update()` throw
`VaultException("duplicated vault name: " + name)` if name already exists.
On startup, all names in `vaults.json` validated — duplicates block startup with a Toast.

**Motivation**: fail fast on inconsistent state.

