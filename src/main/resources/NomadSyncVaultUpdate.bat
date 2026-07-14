@echo off
rem ===========================================================================
rem NomadSync Vault Update
rem Usage: NomadSyncVaultUpdate.bat --vault=<name|owner/name> [--owner=<v>] [--name=<v>] [--path=<v>] [--git.*=<value> ...]
rem
rem Updates the configuration of a registered vault.
rem At least one optional flag must be provided — otherwise no changes are applied.
rem If owner or name change, the remote is re-bootstrapped automatically.
rem
rem Required flags:
rem   --vault=<name|owner/name>   Vault to update (unambiguous name or full owner/name)
rem
rem Optional flags:
rem   --owner=<value>         New GitHub owner
rem   --name=<value>          New vault name
rem   --path=<value>          New local path
rem   --git.name=<value>      Git user.name override
rem   --git.email=<value>     Git user.email override
rem   --git.username=<value>  GitHub username override
rem   --git.token=<value>     GitHub personal access token override
rem   --git.branch=<value>    Git branch override
rem   --git.remote=<value>    Git remote name override
rem
rem Global flags — forwarded to every NomadSync command, documented in full in
rem NomadSyncVault.bat / NomadSync.bat:
rem   --config=<path>   Use an alternate config.properties/vaults.json workspace
rem
rem Examples:
rem   NomadSyncVaultUpdate.bat --vault=notes --git.token=ghp_newtoken
rem   NomadSyncVaultUpdate.bat --vault=Alice/notes --owner=Alice --name=notes-v2
rem ===========================================================================

call NomadSyncVault.bat update %*
