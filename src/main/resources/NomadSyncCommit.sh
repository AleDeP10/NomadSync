#!/bin/bash
# NomadSync Commit Shortcut
# Usage: ./NomadSyncCommit.sh --vault=<name|owner/name> [--config=<path>] [--editor=<path>]
#
# --vault is required — a manual commit without an explicit target is
# intentionally not supported.
#
# Opens the configured text editor for the commit message.
# Editor resolution order:
#   1. --editor flag
#   2. commit.editor in config.properties
#   3. EDITOR environment variable
#   4. nano (Unix default)
#
# Save and close the editor to commit.
# Close without saving to abort — no commit will be created.

call NomadSync.sh commit "$@"
