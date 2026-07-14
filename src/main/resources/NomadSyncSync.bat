@echo off
rem ===========================================================================
rem NomadSync Sync Shortcut
rem Usage: NomadSyncSync.bat [--vault=<name|owner/name>] [--config=<path>]
rem
rem Without --vault: full sync on all registered vaults (broadcast).
rem With --vault:    full sync on the specified vault only.
rem
rem Full bidirectional synchronisation: commit local (if dirty) -> pull ->
rem push. On conflict: local backup snapshot taken first, then pull -X ours
rem (local wins), remote version of each conflicted file saved separately,
rem then push.
rem
rem Global flags — forwarded to every NomadSync command, documented in full in
rem NomadSyncVault.bat / NomadSync.bat:
rem   --config=<path>   Use an alternate config.properties/vaults.json workspace
rem
rem Examples:
rem   NomadSyncSync.bat
rem   NomadSyncSync.bat --vault=notes
rem   NomadSyncSync.bat --vault=Alice/notes --config=C:\vaults\acme\acme.properties
rem ===========================================================================

call NomadSync.bat sync %*
