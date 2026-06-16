#!/bin/bash
# NomadSync Status Shortcut
# Usage: ./NomadSyncStatus.sh [--vault=<name|owner/name>] [--config=<path>]
#
# Without --vault: git status for all registered vaults (with headers).
# With --vault:    git status for the specified vault only.
#
# Use before NomadSyncCommit to review what is staged or modified.

call NomadSync.sh status "$@"
