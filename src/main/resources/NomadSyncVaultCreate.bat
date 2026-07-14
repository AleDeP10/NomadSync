@echo off
rem ===========================================================================
rem NomadSync Vault Create
rem Usage: NomadSyncVaultCreate.bat --owner=<owner> --name=<name> --path=<path> [--git.*=<value> ...]
rem
rem Initialises a brand-new local Git repository and registers it as a vault.
rem Unlike 'add' (which registers an EXISTING repository already on disk),
rem 'create' brings the local repository into existence from scratch.
rem
rem Required flags:
rem   --owner=<owner>   GitHub account/organisation that will own the remote repository
rem   --name=<name>     Remote repository name — combined with --owner, must be
rem                     unique among registered vaults
rem   --path=<path>     Local filesystem path for the vault:
rem                       - absent                       -> created automatically
rem                       - exists and empty              -> used as-is
rem                       - exists, non-empty, no .git/   -> ERROR (refuses to
rem                         overwrite an unrelated directory)
rem                       - exists, already a .git/ repo  -> no-op (use 'add' instead)
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
rem Prerequisite: the remote repository (owner/name) must already exist on
rem GitHub — this command does not create it. 'git push' never creates a
rem remote repository, it only pushes to one that already exists.
rem
rem Examples:
rem   NomadSyncVaultCreate.bat --owner=Alice --name=fresh-vault --path=C:\vaults\fresh-vault
rem   NomadSyncVaultCreate.bat --owner=Acme-Corp --name=notes --path=C:\vaults\notes --git.token=ghp_xxx
rem ===========================================================================

call NomadSyncVault.bat create %*
