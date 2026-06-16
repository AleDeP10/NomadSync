@echo off
rem ===========================================================================
rem NomadSync Status Shortcut
rem Usage: NomadSyncStatus.bat [--vault=<name|owner/name>] [--config=<path>]
rem
rem Without --vault: git status for all registered vaults (with headers).
rem With --vault:    git status for the specified vault only.
rem
rem Use before NomadSyncCommit to review what is staged or modified.
rem ===========================================================================

call NomadSync.bat status %*
