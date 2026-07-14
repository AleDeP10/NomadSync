@echo off
rem ===========================================================================
rem NomadSync Vault Relocate
rem Usage: NomadSyncVaultRelocate.bat --vault=<name|owner/name> [--owner=<new-owner>]
rem        [--name=<new-name>] [--path=<new-path>] [--git.*=<value> ...] [--force]
rem
rem Transfers a vault to a new GitHub owner, PERMANENTLY discarding local Git
rem history and redirecting the remote. Primary use case: migrating a vault
rem from a personal account to an organisation.
rem
rem *** DESTRUCTIVE / IRREVERSIBLE *** — local commit history cannot be
rem recovered once discarded. A backup snapshot of the vault's WORKING FILES
rem (not its Git history, discarded by design) is taken automatically before
rem any destructive step.
rem
rem Required flags:
rem   --vault=<name|owner/name>   Identifies the vault to relocate
rem
rem Optional flags (at least one of owner/name/path must actually change,
rem or the command is refused / is a no-op):
rem   --owner=<value>   New GitHub owner/organisation
rem   --name=<value>    New remote repository name
rem   --path=<value>    New local path. Without this flag the directory is not
rem                     moved. With a different path, the directory is copied
rem                     to the new location first; the original is removed
rem                     only after the copy succeeds.
rem   --git.*=<value>   Credential/config overrides, same semantics as 'update'.
rem                     Providing ONLY --git.* with no structural change is
rem                     refused — use 'update' instead for a pure credential
rem                     rotation.
rem   --force           Skips the interactive y/N confirmation prompt. Intended
rem                     for scripted/non-interactive use. Bypasses ONLY the
rem                     prompt — all other validations still apply.
rem
rem Global flags — forwarded to every NomadSync command, documented in full in
rem NomadSyncVault.bat / NomadSync.bat:
rem   --config=<path>   Use an alternate config.properties/vaults.json workspace
rem
rem Prerequisite: the DESTINATION repository (new owner/name) must already
rem exist on GitHub before running this command — 'git push' never creates a
rem remote repository. Always provide the token/username of the NEW owner
rem when the old account's credentials would not have access to it.
rem
rem Examples:
rem   NomadSyncVaultRelocate.bat --vault=notes --owner=Acme-Corp --git.token=ghp_xxx
rem   NomadSyncVaultRelocate.bat --vault=Alice/notes --path=C:\vaults\notes-new --force
rem ===========================================================================

call NomadSyncVault.bat relocate %*
