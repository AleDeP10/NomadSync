@echo off
rem ===========================================================================
rem NomadSync Vault Show
rem Usage: NomadSyncVaultShow.bat --vault=<name|owner/name> [--defaults]
rem
rem Prints full details of a single registered vault, including git configuration
rem overrides and a live git status summary (clean or list of modified files).
rem
rem Required flags:
rem   --vault=<name|owner/name>   Vault to inspect (unambiguous name or full owner/name)
rem
rem Optional flags:
rem   --defaults   Show all git configuration fields, including those falling
rem                back to config.properties — by default only fields with an
rem                explicit per-vault override are shown.
rem
rem Global flags — forwarded to every NomadSync command, documented in full in
rem NomadSyncVault.bat / NomadSync.bat:
rem   --config=<path>   Use an alternate config.properties/vaults.json workspace
rem
rem Examples:
rem   NomadSyncVaultShow.bat --vault=notes
rem   NomadSyncVaultShow.bat --vault=Alice/notes --defaults
rem ===========================================================================

call NomadSyncVault.bat show %*
