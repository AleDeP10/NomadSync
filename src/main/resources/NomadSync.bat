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
rem   vault    Manage registered vaults (create / add / update / remove / relocate / list / show)
rem
rem Flags:
rem   --daemon        Stay alive indefinitely (Tray/startup mode).
rem                    Without this flag the process exits automatically
rem                    once all queues are drained (one-shot CLI mode).
rem   --config=<path>  Use an alternate config.properties/vaults.json workspace.
rem                    If omitted, defaults to config.properties next to
rem                    NomadSync.jar (not the shell's current directory) —
rem                    resolved by Main.java itself from the running JAR's own
rem                    location. When provided, resolved exactly like any
rem                    normal path: relative to wherever you are standing when
rem                    you run this command, not to this script's location.
rem
rem Working directory:
rem   This script does NOT change the working directory — it locates
rem   NomadSync.jar via an absolute path (%~dp0) so it can be invoked from
rem   anywhere on the PATH, while leaving the shell's current directory
rem   untouched. This matters because any relative --config or --path you
rem   pass is resolved against THAT directory — where you are standing —
rem   letting you keep a workspace's config.properties/vaults.json/log file
rem   together, addressed relative to wherever that workspace lives.
rem ===========================================================================

java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:5005 -jar "%~dp0NomadSync.jar" %*
rem java -jar "%~dp0NomadSync.jar" %*