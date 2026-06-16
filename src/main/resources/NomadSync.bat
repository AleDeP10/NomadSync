@echo off
rem ===========================================================================
rem NomadSync — Main entry point
rem Usage: NomadSync.bat <operation> [--config=<path>] [--vault=<name|owner/name>] [--daemon] [flags...]
rem
rem Operations:
rem   pull     Pull from remote (broadcast if --vault absent)
rem   push     Push to remote   (broadcast if --vault absent)
rem   sync     Full bidirectional sync (broadcast if --vault absent)
rem   status   Show git status  (broadcast if --vault absent)
rem   commit   Local commit with editor message (--vault required)
rem   autosave Periodic autosave — managed by scheduler, no manual use needed
rem   config   Update config.properties or vaults.json
rem
rem Flags:
rem   --daemon   Stay alive indefinitely (Tray/startup mode).
rem              Without this flag the process exits automatically
rem              once all queues are drained (one-shot CLI mode).
rem ===========================================================================

java -jar NomadSync.jar %*