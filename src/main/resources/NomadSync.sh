#!/bin/bash
# NomadSync — Main entry point
# Usage: ./NomadSync.sh <operation> [--config=<path>] [--vault=<name|owner/name>] [flags...]
#
# Operations:
#   pull     Pull from remote (broadcast if --vault absent)
#   push     Push to remote   (broadcast if --vault absent)
#   sync     Full bidirectional sync (broadcast if --vault absent)
#   status   Show git status  (broadcast if --vault absent)
#   commit   Local commit with editor message (--vault required)
#   autosave Periodic autosave — managed by scheduler, no manual use needed
#   config   Update config.properties or vaults.json

java -jar NomadSync.jar "$@"
