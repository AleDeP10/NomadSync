# NomadSync

A lightweight, cross-platform Java tool that keeps one or more folders in sync
across multiple machines using Git and GitHub — no subscription required.

Any folder backed by a private Git repository can be managed: notes,
configuration files, projects, dotfiles, or vaults from tools like Obsidian,
Logseq, or similar. NomadSync supports multiple independent folders ("vaults"),
each mapped to its own GitHub repository and, optionally, its own credentials.

---

## Requirements

- Java 21 or later
- Git installed and available on PATH (or configured in `config.properties`)
- A private GitHub repository for each vault
- Windows (primary), macOS/Linux (supported via `.sh` scripts)

---

## Installation

1. Copy the `target/` folder to the desired location (e.g. `C:\tools\nomadsync\`)
2. Copy `config.properties.template` to `config.properties` and fill in your values
3. Copy `vaults.json.template` to `vaults.json` and register your vaults
4. Add the installation folder to your system PATH to use the scripts from any terminal

---

## Configuration

### `config.properties` — global defaults

```properties
# Git
git.executable=git
git.remote=origin
git.branch=main
git.name=Your Name
git.email=you@example.com
git.username=your-github-username
git.token=ghp_...

# Paths
path.vaults=./vaults.json
path.backup=./backups
path.conflicts=./remote-conflicts

# Logging
log.writers=console,file
log.path=logs/nomadsync.log
log.level=INFO

# Autosave
autosave.interval.minutes=15

# Commit editor (optional — defaults to notepad/nano)
commit.editor=notepad++
```

### `vaults.json` — registered vaults

```json
[
  {
    "id": "auto-generated-uuid",
    "owner": "YourUsername",
    "name": "repository-name",
    "path": "C:\\Users\\you\\vault",
    "gitToken": "ghp_vault_specific_token"
  }
]
```

All `git*` fields in `vaults.json` are **optional**: if absent, the global
values from `config.properties` are used. Useful for vaults owned by other
users, or legacy repositories using `master` instead of `main`.

> **Security**: `vaults.json` is excluded from version control — it may contain
> credentials. Only `vaults.json.template` is committed. The token is stored in
> the vault's local `.git/config` (never committed) and never appears in logs.

---

## Usage

### Main operations

```bat
REM Full bidirectional sync (pull + conflict resolution + push)
NomadSyncSync.bat --vault=vault-name

REM Pull at session start (broadcasts to all vaults if --vault absent)
NomadSyncPull.bat

REM Push at session end
NomadSyncPush.bat

REM Local commit with custom message (opens editor)
NomadSyncCommit.bat --vault=vault-name

REM Show git status
NomadSyncStatus.bat
NomadSyncStatus.bat --vault=vault-name
```

### Configuration management

```bat
REM Update global token (config.properties)
NomadSyncConfig.bat --git.token=ghp_new_token

REM Update token for a specific vault (vaults.json)
NomadSyncConfig.bat --vault=vault-name --git.token=ghp_vault_token

REM Change branch for a legacy repository
NomadSyncConfig.bat --vault=legacy-vault --git.branch=master
```

### Vault resolution

`--vault` accepts the vault name or its full repoSlug:

```bat
NomadSyncSync.bat --vault=public-vault
NomadSyncSync.bat --vault=YourUsername/public-vault
```

If multiple vaults share the same name (different owners), NomadSync requires
the full repoSlug:

```
vault name 'public-vault' is ambiguous.
Matches: AleDeP10/public-vault, Belmani/public-vault.
Use --vault=<owner>/<name>
```

---

## Interactive commit

`NomadSyncCommit` opens the configured text editor, waits for it to close, and
uses the saved text as the commit message.

- **Save and close** → commit created
- **Close without saving** → operation cancelled, no commit

Editor resolution order:
1. `--editor` flag on the command line
2. `commit.editor` in `config.properties`
3. `EDITOR` environment variable
4. `notepad` on Windows, `nano` on Unix

---

## Conflict resolution

When a conflict occurs during `sync`:

1. NomadSync creates a FIFO snapshot of the vault in `backups/`
   (max 3 snapshots per vault)
2. Applies `git pull -X ours` — local version wins
3. Saves the remote version of each conflicted file in `remote-conflicts/`
   for manual review

Backup and conflict directories use the format `<owner>_<name>_<timestamp>`,
ensuring no collision between vaults with the same name but different owners.

---

## Daemon mode

By default, NomadSync exits automatically once all operations are complete
(one-shot mode). Pass `--daemon` to keep the process alive indefinitely
(for use with the Tray, coming in a future release):

```bat
NomadSync.bat pull --daemon
```

---

## Task Scheduler (Windows)

To automate pull at logon and push at logoff:

```
Trigger: At user logon → Action: NomadSyncPull.bat
Trigger: At disconnect  → Action: NomadSyncPush.bat
```

Without `--vault`, both operate on all registered vaults.

---

## Troubleshooting

### `Repository not found` on pull/push

1. Verify the repository exists on GitHub with the exact name in `vaults.json`
2. Check the token has not expired:
   GitHub → Settings → Developer settings → Personal access tokens
3. Verify the token has `repo` scope (full control of private repositories)
4. Re-run bootstrap: `NomadSyncConfig.bat --vault=<name> --git.token=ghp_new`
5. Check the remote URL: `git -C <vault-path> remote get-url origin`
   — should start with `https://ghp_...@github.com/`

### Process does not terminate after pull/push

Without `--daemon`, the process exits automatically once all queues are drained.
If it hangs, it is likely waiting for a network operation (retry with backoff).
Check the log for `Network error` entries. The process will exit after at most
3 retries (~3.5 minutes).

### Token visible in logs

The token is never logged by NomadSync. If you see it in a log file, it was
passed as a command-line argument by an external script — review your wrapper
scripts and use `NomadSyncConfig.bat --git.token=...` instead.

### Empty `git status` output on one line

Ensure you are using `NomadSyncStatus.bat` (which calls `Main` with `status`)
and not calling `git status` directly from a script that strips newlines.
