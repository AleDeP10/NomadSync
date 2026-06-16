#!/bin/bash
# Usage: NomadSyncCommit.sh [config.properties] <vaultId>
#
# Opens a text editor for a commit message, then performs a local-only
# commit on the specified vault. Requires vaultId — a manual commit
# without an explicit target is intentionally not supported.

if [ -z "$2" ]; then
    echo "Usage: NomadSyncCommit.sh [config.properties] <vaultId>"
    echo "vaultId is required for manual commits."
    exit 1
fi

CONFIG="$1"
VAULTID="$2"
TMPFILE=$(mktemp /tmp/nomadsync-commit.XXXXXX)

"${EDITOR:-nano}" "$TMPFILE"

# Check if the file has any non-whitespace content
if [ ! -s "$TMPFILE" ] || [ -z "$(tr -d '[:space:]' < "$TMPFILE")" ]; then
    echo "Empty commit message — aborting, no commit created."
    rm -f "$TMPFILE"
    exit 0
fi

./NomadSync.sh commit "$CONFIG" "$VAULTID" "$TMPFILE"
rm -f "$TMPFILE"
