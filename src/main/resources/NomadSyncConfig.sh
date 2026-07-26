#!/bin/bash
# NomadSync Config Shortcut
# Usage: ./NomadSyncConfig.sh [--vault=<name|owner/name>] [--config=<path>] --git.<key>=<value> [...]
#
# Without --vault: updates config.properties (global defaults for all vaults).
# With --vault:    updates catalog.json for the specified vault only.
#                  Changes are applied immediately via bootstrapVault.
#
# Supported --git.* flags:
#   --git.name=<value>      Git user.name for commits
#   --git.email=<value>     Git user.email for commits
#   --git.username=<value>  GitHub username for authentication
#   --git.token=<value>     GitHub personal access token
#   --git.branch=<value>    Git branch (e.g. master for legacy repos)
#   --git.remote=<value>    Git remote name (e.g. upstream)

call NomadSync.sh config "$@"
