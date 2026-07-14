@echo off
rem ===========================================================================
rem NomadSync Config Shortcut
rem Usage: NomadSyncConfig.bat [--vault=<name|owner/name>] --git.<key>=<value> [...] [--config=<path>]
rem
rem Without --vault: updates config.properties (global defaults for all vaults).
rem With --vault:    updates vaults.json for the specified vault only.
rem                  Changes are applied immediately via bootstrapVault.
rem
rem Supported --git.* flags:
rem   --git.name=<value>      Git user.name for commits
rem   --git.email=<value>     Git user.email for commits
rem   --git.username=<value>  GitHub username for authentication
rem   --git.token=<value>     GitHub personal access token
rem   --git.branch=<value>    Git branch (e.g. master for legacy repos)
rem   --git.remote=<value>    Git remote name (e.g. upstream)
rem
rem Global flags — forwarded to every NomadSync command, documented in full in
rem NomadSyncVault.bat / NomadSync.bat:
rem   --config=<path>   Use an alternate config.properties/vaults.json workspace
rem
rem Examples:
rem   NomadSyncConfig.bat --git.token=ghp_...
rem   NomadSyncConfig.bat --git.name="Alice" --git.email=alice@example.com
rem   NomadSyncConfig.bat --vault=public-vault --git.token=ghp_...
rem   NomadSyncConfig.bat --vault=Alice/legacy-vault --git.branch=master
rem ===========================================================================

call NomadSync.bat config %*
