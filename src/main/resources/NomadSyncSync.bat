@echo off
rem ===========================================================================
rem NomadSync Sync Shortcut
rem Usage: NomadSyncSync.bat [--vault=<name|owner/name>] [--config=<path>]
rem
rem Without --vault: full sync on all registered vaults (broadcast).
rem With --vault:    full sync on the specified vault only.
rem ===========================================================================

call NomadSync.bat sync %*
