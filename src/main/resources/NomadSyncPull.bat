@echo off
rem ===========================================================================
rem NomadSync Pull Shortcut
rem Usage: NomadSyncPull.bat [--vault=<name|owner/name>] [--config=<path>]
rem
rem Without --vault: pull on all registered vaults (broadcast).
rem With --vault:    pull on the specified vault only.
rem ===========================================================================

call NomadSync.bat pull %*
