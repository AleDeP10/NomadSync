@echo off
rem ===========================================================================
rem NomadSync Commit Shortcut
rem Usage: NomadSyncCommit.bat --vault=<name|owner/name> [--config=<path>] [--editor=<path>]
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
rem ===========================================================================

call NomadSync.bat commit %*
