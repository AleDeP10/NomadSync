@echo off
rem ===========================================================================
rem NomadSync Commit Shortcut
rem Usage: NomadSyncCommit.bat --vault=<name|owner/name> [--editor=<path>] [--config=<path>]
rem
rem --vault is required — a manual commit without an explicit target is
rem intentionally not supported.
rem
rem Opens the configured text editor for the commit message.
rem Editor resolution order:
rem   1. --editor flag
rem   2. commit.editor in config.properties
rem   3. EDITOR environment variable
rem   4. notepad (Windows default)
rem
rem Save and close the editor to commit.
rem Close without saving to abort — no commit will be created.
rem
rem Global flags — forwarded to every NomadSync command, documented in full in
rem NomadSyncVault.bat / NomadSync.bat:
rem   --config=<path>   Use an alternate config.properties/vaults.json workspace
rem
rem Examples:
rem   NomadSyncCommit.bat --vault=notes
rem   NomadSyncCommit.bat --vault=Alice/notes --editor=notepad++
rem   NomadSyncCommit.bat --vault=notes --config=C:\vaults\acme\acme.properties
rem ===========================================================================

call NomadSync.bat commit %*
