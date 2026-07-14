@echo off
rem ===========================================================================
rem NomadSync Status Shortcut
rem Usage: NomadSyncStatus.bat [--vault=<name|owner/name>] [--config=<path>]
rem
rem Without --vault: git status for all registered vaults (with headers).
rem With --vault:    git status for the specified vault only.
rem
rem Use before NomadSyncCommit to review what is staged or modified.
rem
rem Global flags — forwarded to every NomadSync command, documented in full in
rem NomadSyncVault.bat / NomadSync.bat:
rem   --config=<path>   Use an alternate config.properties/vaults.json workspace
rem
rem Examples:
rem   NomadSyncStatus.bat
rem   NomadSyncStatus.bat --vault=notes
rem   NomadSyncStatus.bat --vault=Alice/notes --config=C:\vaults\acme\acme.properties
rem ===========================================================================

call NomadSync.bat status %*
