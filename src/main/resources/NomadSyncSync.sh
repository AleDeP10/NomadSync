#!/bin/bash
# NomadSync Sync Shortcut
# Usage: ./NomadSyncSync.sh [--vault=<name|owner/name>] [--config=<path>]
#
# Without --vault: full sync on all registered vaults (broadcast).
# With --vault:    full sync on the specified vault only.

call NomadSync.sh sync "$@"
