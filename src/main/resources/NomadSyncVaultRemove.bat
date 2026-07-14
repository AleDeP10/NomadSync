@echo off
rem ===========================================================================
rem NomadSync Vault Remove
rem Usage: NomadSyncVaultRemove.bat --vault=<name|owner/name> [--force]
rem
rem Removes a vault from the registry. The local directory and the remote
rem repository are NOT affected — only the NomadSync registration is deleted.
rem
rem Confirmation is required interactively before deletion proceeds.
rem Default answer is N — press Enter to abort safely.
rem
rem Required flags:
rem   --vault=<name|owner/name>   Vault to remove (unambiguous name or full owner/name)
rem
rem Optional flags:
rem   --force   Skips the interactive y/N confirmation prompt. Intended for
rem             scripted/non-interactive use.
rem
rem Global flags — forwarded to every NomadSync command, documented in full in
rem NomadSyncVault.bat / NomadSync.bat:
rem   --config=<path>   Use an alternate config.properties/vaults.json workspace
rem
rem Examples:
rem   NomadSyncVaultRemove.bat --vault=notes
rem   NomadSyncVaultRemove.bat --vault=Alice/notes --force
rem ===========================================================================

call NomadSyncVault.bat remove %*
