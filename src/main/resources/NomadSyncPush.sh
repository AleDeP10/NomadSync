#!/bin/bash
# NomadSync Push Shortcut
# Usage: ./NomadSyncPush.sh [--vault=<name|owner/name>] [--config=<path>]
#
# Without --vault: push on all registered vaults (broadcast).
# With --vault:    push on the specified vault only.

call NomadSync.sh push "$@"
