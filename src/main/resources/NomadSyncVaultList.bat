@echo off
rem ===========================================================================
rem NomadSync Vault List
rem Usage: NomadSyncVaultList.bat
rem
rem Prints a table of all registered vaults with their local path.
rem No subcommand-specific flags required.
rem
rem Global flags — forwarded to every NomadSync command, documented in full in
rem NomadSyncVault.bat / NomadSync.bat:
rem   --config=<path>   Use an alternate config.properties/vaults.json workspace
rem
rem Examples:
rem   NomadSyncVaultList.bat
rem   NomadSyncVaultList.bat --config=C:\vaults\acme\acme.properties
rem ===========================================================================

call NomadSyncVault.bat list %*
