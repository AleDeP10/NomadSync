@echo off
rem ===========================================================================
rem NomadSync Vault — Core vault management entry point
rem Usage: NomadSyncVault.bat <subcommand> [flags...]
rem
rem Subcommands:
rem   create    Initialise a new local Git repository and register it as a
rem             vault (requires --owner, --name, --path)
rem   add       Register a new vault backed by an existing local Git
rem             repository (requires --owner, --name, --path)
rem   update    Update an existing vault (requires --vault)
rem   remove    Remove a vault from the registry (requires --vault;
rem             --force skips the interactive confirmation)
rem   relocate  Transfer a vault to a new GitHub owner, discarding local Git
rem             history (requires --vault; DESTRUCTIVE — --force skips the
rem             interactive confirmation)
rem   list      List all registered vaults
rem   show      Show details of a single vault (requires --vault)
rem
rem This command requires admin-level access — vault registration modifies
rem the shared vaults.json registry and affects all operations on this machine.
rem
rem Global flags (all subcommands):
rem   --config=<path>   Use an alternate config.properties/vaults.json
rem                     workspace instead of the one adjacent to NomadSync.jar.
rem                     Relative paths resolve against your current directory
rem                     at invocation time, not against this script's location
rem                     — see NomadSync.bat for the full explanation. Useful
rem                     to keep a client's own config.properties, vaults.json,
rem                     and log file together, addressed as one workspace.
rem
rem Examples:
rem   NomadSyncVault.bat create --owner=Alice --name=fresh --path=C:\vaults\fresh
rem   NomadSyncVault.bat add --owner=Alice --name=notes --path=C:\vaults\notes
rem   NomadSyncVault.bat list
rem   NomadSyncVault.bat show --vault=notes
rem   NomadSyncVault.bat remove --vault=Alice/notes --force
rem   NomadSyncVault.bat relocate --vault=Alice/notes --owner=Acme-Corp
rem   NomadSyncVault.bat list --config=C:\vaults\acme\acme.properties
rem ===========================================================================

call NomadSync.bat vault %*