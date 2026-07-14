@echo off
rem ===========================================================================
rem NomadSync Push Shortcut
rem Usage: NomadSyncPush.bat [--vault=<name|owner/name>] [--config=<path>]
rem
rem Without --vault: push on all registered vaults (broadcast).
rem With --vault:    push on the specified vault only.
rem
rem Global flags — forwarded to every NomadSync command, documented in full in
rem NomadSyncVault.bat / NomadSync.bat:
rem   --config=<path>   Use an alternate config.properties/vaults.json workspace
rem
rem Examples:
rem   NomadSyncPush.bat
rem   NomadSyncPush.bat --vault=notes
rem   NomadSyncPush.bat --vault=Alice/notes --config=C:\vaults\acme\acme.properties
rem ===========================================================================

call NomadSync.bat push %*
