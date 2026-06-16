@echo off
rem ===========================================================================
rem NomadSync Push Shortcut
rem Usage: NomadSyncPush.bat [--vault=<name|owner/name>] [--config=<path>]
rem
rem Without --vault: push on all registered vaults (broadcast).
rem With --vault:    push on the specified vault only.
rem ===========================================================================

call NomadSync.bat push %*
