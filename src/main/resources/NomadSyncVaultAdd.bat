@echo off
rem ===========================================================================
rem NomadSync Vault Add
rem Usage: NomadSyncVaultAdd.bat --owner=<owner> --name=<name> --path=<path> [--git.*=<value> ...]
rem
rem Registers a new vault backed by an existing local Git repository.
rem The path must exist and contain a .git directory.
rem
rem Required flags:
rem   --owner=<owner>   GitHub account that owns the remote repository
rem   --name=<name>     Vault name — must match the remote repository name
rem   --path=<path>     Absolute path to the local vault directory
rem
rem Optional git overrides (applied to this vault only):
rem   --git.name=<value>      Git user.name for commits
rem   --git.email=<value>     Git user.email for commits
rem   --git.username=<value>  GitHub username for authentication
rem   --git.token=<value>     GitHub personal access token
rem   --git.branch=<value>    Git branch override (e.g. master for legacy repos)
rem   --git.remote=<value>    Git remote name override (e.g. upstream)
rem
rem Global flags — forwarded to every NomadSync command, documented in full in
rem NomadSyncVault.bat / NomadSync.bat:
rem   --config=<path>   Use an alternate config.properties/vaults.json workspace
rem
rem Examples:
rem   NomadSyncVaultAdd.bat --owner=Alice --name=notes --path=C:\vaults\notes
rem   NomadSyncVaultAdd.bat --owner=Bob --name=legacy --path=C:\vaults\legacy --git.branch=master
rem   NomadSyncVaultAdd.bat --owner=Acme-Corp --name=shared --path=C:\vaults\shared --config=C:\vaults\acme\acme.properties
rem ===========================================================================

call NomadSyncVault.bat add %*
