#!/bin/bash
# NomadSync Pull Shortcut
# Usage: ./NomadSyncPull.sh [--vault=<name|owner/name>] [--config=<path>]
#
# Without --vault: pull on all registered vaults (broadcast).
# With --vault:    pull on the specified vault only.

call NomadSync.sh pull "$@"
