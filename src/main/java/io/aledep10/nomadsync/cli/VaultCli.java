package io.aledep10.nomadsync.cli;

import io.aledep10.nomadsync.Main;
import io.aledep10.nomadsync.exception.*;
import io.aledep10.nomadsync.gitignore.exception.GitignoreException;
import io.aledep10.nomadsync.service.GitService;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.service.MarkerService;
import io.aledep10.nomadsync.service.VaultService;
import io.aledep10.nomadsync.util.FileUtil;
import io.aledep10.nomadsync.vault.Vault;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static io.aledep10.nomadsync.service.VaultService.*;

public class VaultCli {

    public static int execute(String subcommand, Map<String, String> flags, List<Vault> vaults,
                              VaultService vaultService, GitService gitService,
                              MarkerService markerService, LogService logService) {
        return switch (subcommand) {
            case "create"   -> handleVaultCreate(flags, vaults, vaultService, gitService, logService);
            case "add"      -> handleVaultAdd(flags, vaults, vaultService, gitService, logService);
            case "update"   -> handleVaultUpdate(flags, vaults, vaultService, gitService, logService);
            case "remove"   -> handleVaultRemove(flags, vaults, vaultService, logService);
            case "relocate" -> handleVaultRelocate(flags, vaults, vaultService, markerService, gitService, logService);
            case "list"     -> handleVaultList(flags, vaults, logService);
            case "show"     -> {
                int maxLines = Integer.parseInt(flags.getOrDefault("maxLines", "5"));
                yield handleVaultShow(flags, vaults, maxLines, gitService, logService);
            }
            default -> {
                logService.error("Unknown vault subcommand: " + subcommand +
                        ". Use: create | add | update | remove | relocate | list |  show");
                yield 1;
            }
        };
    }

    // ── Vault CLI handlers ────────────────────────────────────────────────────

    // Allowed flags per vault subcommand.
    // "sub" is injected internally by the parser and is always implicitly allowed.
    // Global flags (workspacePath, vault, daemon) are removed from the map before these
    // handlers are invoked, so they must not appear here.
    private static final Set<String> FLAGS_VAULT_ADD    =
            Set.of("owner", "name", "path",
                    "git.name", "git.email", "git.username",
                    "git.token", "git.branch", "git.remote");
    // Allowed flags — same set as FLAGS_VAULT_ADD (owner, name, path, git.*).
    // git.token is NOT mandatory here: it may come from config.properties defaults.
    private static final Set<String> FLAGS_VAULT_CREATE = FLAGS_VAULT_ADD;
    private static final Set<String> FLAGS_VAULT_UPDATE =
            Set.of("vault", "owner", "name", "path",
                    "git.name", "git.email", "git.username",
                    "git.token", "git.branch", "git.remote");
    private static final Set<String> FLAGS_VAULT_REMOVE = Set.of("vault", Main.FLAG_FORCE);
    private static final Set<String> FLAGS_VAULT_RELOCATE =
            Set.of("vault", "owner", "name", "path",
                    "git.name", "git.email", "git.username",
                    "git.token", "git.branch", "git.remote", Main.FLAG_FORCE);
    private static final Set<String> FLAGS_VAULT_LIST   = Set.of();
    private static final Set<String> FLAGS_VAULT_SHOW   = Set.of("vault", "defaults");

    /**
     * Detects any flag keys in {@code flags} that do not belong to the given
     * known set for the current subcommand.
     *
     * <p>The internal {@code "sub"} key injected by the parser is always
     * permitted and never reported. For each unrecognised key, logs one error
     * line — including a "did you mean...?" suggestion (via
     * {@link #nearestKnownFlag}) when a known flag is within Levenshtein
     * distance {@link #FLAG_SUGGESTION_MAX_DISTANCE}, to help catch typos.</p>
     *
     * @param flags      parsed CLI flags (global flags already removed)
     * @param knownFlags set of keys valid for the current subcommand
     * @param handler    handler name used as log prefix, e.g. {@code "handleVaultAdd"}
     * @param logService shared logging service
     * @return {@code true} if at least one unrecognised key is present,
     *         {@code false} if all keys are recognised
     */
    private static boolean hasUnknownFlags(Map<String, String> flags, Set<String> knownFlags,
                                           String handler, LogService logService) {
        List<String> unknown = flags.keySet().stream()
                .filter(k -> !k.equals("sub") && !knownFlags.contains(k))
                .sorted()
                .toList();
        if (unknown.isEmpty()) return false;

        unknown.forEach(k -> {
            Optional<String> suggestion = nearestKnownFlag(k, knownFlags);
            String message = "unknown flag '--" + k + "'"
                    + suggestion.map(s -> " — did you mean '--" + s + "'?").orElse("");
            logService.error(handler + ": " + message);
        });
        return true;
    }

    /**
     * Handles {@code vault create} — initialises a brand-new local Git repository
     * at {@code --path} and registers it as a vault, in that order.
     *
     * <p>Unlike {@code vault add}, which registers a repository that already
     * exists on disk, {@code create} is responsible for bringing the local
     * repository into existence. The sequence is deliberately
     * {@code init() → create()}, not the reverse: a crash between the two leaves
     * an orphaned, unregistered {@code .git/} directory (harmless — {@code init}
     * is idempotent, and {@code vault add} can pick it up later), rather than a
     * registered vault pointing at a non-repository path (which every other
     * command would then silently mishandle).</p>
     *
     * <h2>Path precondition</h2>
     * <ul>
     *   <li>path absent → created via {@code mkdirs()}</li>
     *   <li>path exists but is not a directory → error</li>
     *   <li>path exists, already contains {@code .git} → no-op, not an error
     *       (use {@code vault add} instead)</li>
     *   <li>path exists, non-empty, no {@code .git} → error — refuses to run
     *       {@code git init} into an unrelated non-empty directory</li>
     *   <li>path exists, empty → proceeds without calling {@code mkdirs()}</li>
     * </ul>
     *
     * <p>Required flags: {@code --owner}, {@code --name}, {@code --path}.
     * Optional: {@code --git.*} (applied after registration, same as
     * {@code vault add}).</p>
     *
     * @param flags        parsed CLI flags
     * @param vaults       the list of already-registered vaults, used to pre-check
     *                     {@code repoSlug} duplication before touching the filesystem
     * @param vaultService vault persistence service
     * @param gitService   Git operations service
     * @param logService   shared logging service
     * @return {@code 0} on success; {@code 1} on error (missing/unknown flags,
     *         path not a directory, non-empty target directory,
     *         {@code init}/{@code create}/{@code bootstrapVault} failure);
     *         {@code 2} if the vault is already registered or the path already
     *         contains a Git repository (no-op)
     */
    public static int handleVaultCreate(Map<String, String> flags, List<Vault> vaults,
                                        VaultService vaultService,
                                        GitService gitService,
                                        LogService logService) {
        if (hasUnknownFlags(flags, FLAGS_VAULT_CREATE, "handleVaultCreate", logService)) return 1;
        if (hasBlankRequiredFlags(flags, Set.of("owner", "name", "path"), "handleVaultCreate", logService)) return 1;

        String owner    = flags.get("owner");
        String name     = flags.get("name");
        String path     = flags.get("path");
        String repoSlug = owner + "/" + name;

        if (vaults.stream().anyMatch(v -> v.getRepoSlug().equals(repoSlug))) {
            logService.warn("handleVaultCreate - " + repoSlug + " - already registered, skipping");
            return 2;
        }

        File pathDir = new File(path);
        if (pathDir.exists()) {
            if (!pathDir.isDirectory()) {
                logService.error("handleVaultCreate - " + path + " - exists and is not a directory");
                return 1;
            }
            if (new File(pathDir, ".git").exists()) {
                logService.warn("handleVaultCreate - " + path + " - already a git repository, skipping");
                return 2;
            }
            String[] contents = pathDir.list();
            if (contents == null) {
                logService.error("handleVaultCreate - " + path + " - unable to list directory contents");
                return 1;
            }
            if (contents.length > 0) {
                logService.error("handleVaultCreate - " + path + " - exists and is not empty");
                return 1;
            }
            // exists, is a directory, and is empty -> proceed without mkdirs()
        } else {
            if (!pathDir.mkdirs()) {
                logService.error("handleVaultCreate - " + path + " - failed to create directory");
                return 1;
            }
        }

        Vault temp = new Vault(UUID.randomUUID().toString(), owner, name, path);
        try {
            gitService.init(temp);
        } catch (GitException e) {
            logService.error("handleVaultCreate - init failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logService.error("handleVaultCreate - init interrupted: " + e.getMessage());
            return 1;
        }

        Vault vault;
        try {
            vault = vaultService.create(owner, name, path);
        } catch (VaultException e) {
            logService.error("handleVaultCreate - registration failed: " + e.getMessage());
            return 1;
        }

        Map<String, String> gitFlags = new LinkedHashMap<>();
        flags.forEach((k, v) -> {
            if (k.startsWith("git.")) gitFlags.put(k, v);
        });
        applyGitFlagsToVault(gitFlags, vault, logService);

        try {
            gitService.bootstrapVault(vault);
        } catch (GitException e) {
            logService.error("handleVaultCreate - bootstrapVault failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logService.error("handleVaultCreate - bootstrapVault interrupted: " + e.getMessage());
            return 1;
        }

        logService.info("Vault created: " + vault.getRepoSlug());
        return 0;
    }

    /**
     * Handles {@code vault add} — registers a new vault.
     *
     * <p>The specified path must exist on the local filesystem and contain a
     * {@code .git} directory. Git credential overrides can be provided via
     * {@code --git.*} flags and are applied immediately after registration.</p>
     *
     * <p>If {@code repoSlug} ({@code owner/name}) is already registered, this is
     * treated as a no-op, not an error — mirrors {@code vault create}. A duplicated
     * {@code path} is not pre-checked here and remains a real error, surfaced via
     * {@link VaultService#create(String, String, String)} throwing
     * {@link VaultException}.</p>
     *
     * <p>Required flags: {@code --owner}, {@code --name}, {@code --path}.</p>
     *
     * @param flags        parsed CLI flags
     * @param vaults       the list of already-registered vaults, used to pre-check
     *                     {@code repoSlug} duplication before touching the filesystem
     * @param vaultService vault persistence service
     * @param gitService   Git operations service
     * @param logService   shared logging service
     * @return {@code 0} on success, {@code 1} on any error, {@code 2} if the
     *         {@code repoSlug} is already registered (no-op)
     */
    public static int handleVaultAdd(Map<String, String> flags, List<Vault> vaults,
                                     VaultService vaultService,
                                     GitService gitService,
                                     LogService logService) {
        if (hasUnknownFlags(flags, FLAGS_VAULT_ADD, "handleVaultAdd", logService)) return 1;
        if (hasBlankRequiredFlags(flags, Set.of("owner", "name", "path"), "handleVaultAdd", logService)) return 1;

        String owner    = flags.get("owner");
        String name     = flags.get("name");
        String path     = flags.get("path");
        String repoSlug = owner + "/" + name;

        if (vaults.stream().anyMatch(v -> v.getRepoSlug().equals(repoSlug))) {
            logService.warn("handleVaultAdd - " + repoSlug + " - already registered, skipping");
            return 2;
        }

        File pathDir = new File(path);
        if (!pathDir.exists() || !pathDir.isDirectory()) {
            logService.error("handleVaultAdd: path does not exist: " + path);
            return 1;
        }
        if (!new File(pathDir, ".git").exists()) {
            logService.error("handleVaultAdd: path is not a git repository: " + path);
            return 1;
        }

        Vault vault;
        try {
            vault = vaultService.create(owner, name, path);
        } catch (VaultException e) {
            logService.error("handleVaultAdd - " + e.getMessage());
            return 1;
        }

        Map<String, String> gitFlags = new LinkedHashMap<>();
        flags.forEach((k, v) -> {
            if (k.startsWith("git.")) gitFlags.put(k, v);
        });
        applyGitFlagsToVault(gitFlags, vault, logService);

        try {
            gitService.bootstrapVault(vault);
        } catch (GitException e) {
            logService.error("handleVaultAdd - bootstrapVault failed: " + e.getMessage(), e);
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logService.error("handleVaultAdd - bootstrapVault interrupted: " + e.getMessage(), e);
            return 1;
        }

        logService.info("Vault added: " + vault.getRepoSlug());
        return 0;
    }

    /**
     * Handles {@code vault update} — updates configuration of an existing vault.
     *
     * <p>At least one optional flag must be provided — if none are present, the
     * command is a no-op and returns {@code 2}. If {@code owner} or
     * {@code name} change, {@link GitService#bootstrapVault(Vault)} is called to
     * re-apply the updated remote URL. Bootstrap is also called for credential
     * changes ({@code git.*}) to propagate the new values to the local Git config.</p>
     *
     * <p>Required flags: {@code --vault}. Optional: {@code --owner}, {@code --name},
     * {@code --path}, {@code --git.*}.</p>
     *
     * @param flags        parsed CLI flags
     * @param vaults       the list of registered vaults
     * @param vaultService vault persistence service
     * @param gitService   Git operations service
     * @param logService   shared logging service
     * @return {@code 0} on success, {@code 1} on any error, {@code 2} if no
     *         changes were requested (no-op)
     */
    public static int handleVaultUpdate(Map<String, String> flags, List<Vault> vaults,
                                        VaultService vaultService,
                                        GitService gitService,
                                        LogService logService) {
        if (hasUnknownFlags(flags, FLAGS_VAULT_UPDATE, "handleVaultUpdate", logService)) return 1;
        if (hasBlankRequiredFlags(flags, Set.of("vault"), "handleVaultUpdate", logService)) return 1;
        if (hasBlankOptionalValue(flags, Set.of("owner", "name", "path"), "handleVaultUpdate", logService)) return 1;

        Vault vault;
        try {
            vault = resolveVaultFlag(flags.get("vault"), vaults);
        } catch (VaultNotFoundException | VaultAmbiguousException e) {
            logService.error("handleVaultUpdate - " + e.getMessage());
            listRegistered(vaults, logService);
            return 1;
        }

        boolean changed = false;
        if (flags.containsKey("owner")) { vault.setOwner(flags.get("owner")); changed = true; }
        if (flags.containsKey("name"))  { vault.setName(flags.get("name"));   changed = true; }
        if (flags.containsKey("path"))  { vault.setPath(flags.get("path"));   changed = true; }

        Map<String, String> gitFlags = new LinkedHashMap<>();
        flags.forEach((k, v) -> { if (k.startsWith("git.")) gitFlags.put(k, v); });
        if (!gitFlags.isEmpty()) {
            applyGitFlagsToVault(gitFlags, vault, logService);
            changed = true;
        }

        if (!changed) {
            logService.info("handleVaultUpdate: no changes requested.");
            return 2;
        }

        try {
            vaultService.update(vault);
        } catch (VaultException e) {
            logService.error("handleVaultUpdate - " + e.getMessage(), e);
            return 1;
        }

        try {
            gitService.bootstrapVault(vault);
        } catch (GitException e) {
            logService.error("handleVaultUpdate - bootstrapVault failed: " + e.getMessage(), e);
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logService.error("handleVaultUpdate - bootstrapVault interrupted: " + e.getMessage(), e);
            return 1;
        }

        logService.info("Vault updated: " + vault.getRepoSlug());
        return 0;
    }

    /**
     * Handles {@code vault remove} — removes a vault from the registry.
     *
     * <p>The local directory and the remote repository are not affected —
     * only the NomadSync registration is deleted from {@code catalog.json}.
     * Interactive confirmation is required; the default answer is {@code N}.
     * Declining the confirmation is a legitimate no-op, not an error.</p>
     *
     * <p>{@code --force}, if present, bypasses the confirmation prompt entirely
     * and proceeds directly to deletion — intended for scripted/non-interactive
     * use. Same bypass semantics as {@code vault relocate}.</p>
     *
     * <p>Required flags: {@code --vault}. Optional: {@code --force}.</p>
     *
     * @param flags        parsed CLI flags
     * @param vaults       the list of registered vaults
     * @param vaultService vault persistence service
     * @param logService   shared logging service
     * @return {@code 0} on success, {@code 1} on any error, {@code 2} if the
     *         user declines the confirmation prompt (no-op)
     */
    public static int handleVaultRemove(Map<String, String> flags, List<Vault> vaults,
                                        VaultService vaultService,
                                        LogService logService) {
        if (hasUnknownFlags(flags, FLAGS_VAULT_REMOVE, "handleVaultRemove", logService)) return 1;
        if (hasBlankRequiredFlags(flags, Set.of("vault"), "handleVaultRemove", logService)) return 1;
        if (hasBlankOptionalValue(flags, Set.of("owner", "name", "path"), "handleVaultRemove", logService)) return 1;

        Vault vault;
        try {
            vault = resolveVaultFlag(flags.get("vault"), vaults);
        } catch (VaultNotFoundException | VaultAmbiguousException e) {
            logService.error("handleVaultRemove - " + e.getMessage());
            listRegistered(vaults, logService);
            return 1;
        }

        if (!flags.containsKey(Main.FLAG_FORCE)) {
            System.out.print("Remove vault " + vault.getRepoSlug() + "? (y/N): ");

            int response;
            try {
                response = System.in.read();
            } catch (IOException e) {
                logService.error("handleVaultRemove - failed to read user input: " + e.getMessage(), e);
                return 1;
            }

            if (response != 'y' && response != 'Y') {
                logService.info("Aborted.");
                return 2;
            }
        }

        try {
            vaultService.delete(vault.getId());
            logService.info("Vault removed: " + vault.getRepoSlug());
            return 0;
        } catch (VaultException e) {
            logService.error("handleVaultRemove - delete failed: " + e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Handles {@code vault relocate} — transfers a vault to a new GitHub owner,
     * resetting local Git history and redirecting the remote.
     *
     * <p>Primary use case: migrating a vault from a personal account to an
     * organisation. The physical directory is moved only if {@code --path}
     * differs from the vault's current path; {@code --owner}, {@code --name},
     * and credentials ({@code --git.*}) are all optional and default to their
     * current values when omitted, mirroring {@code vault update}.</p>
     *
     * <h2>Nesting pre-check</h2>
     * <p>If {@code --path} differs from the vault's current path, {@link
     * MarkerService#checkNoNestingConflict} is consulted <strong>before</strong> the
     * confirmation prompt (and before {@code --force} can bypass it) — a
     * destination that is nested inside, or would contain, another vault's already
     * claimed directory aborts the operation immediately, before
     * {@link GitService#reset} has any chance to discard local history. This
     * pre-check does not replace {@link VaultService#update}'s own claim/release
     * logic (which still runs later, once the physical move has already
     * succeeded) — it exists specifically to fail fast, before any destructive
     * step, not merely before persistence.</p>
     *
     * <h2>Destructive operation — safety measures</h2>
     * <ul>
     *   <li>A {@link VaultService#makeVaultSnapshot} backup of the vault's
     *       <em>working files</em> is taken before any destructive step. This
     *       protects the notes/content from an unrelated mishap during the move —
     *       it does <strong>not</strong> preserve Git history, which is discarded
     *       by design, not by accident.</li>
     *   <li>Interactive {@code y/N} confirmation is required unless {@code --force}
     *       is present — same bypass mechanism as {@code vault remove}.</li>
     * </ul>
     *
     * <h2>Sequence and its rationale</h2>
     * <ol>
     *   <li>{@link VaultService#makeVaultSnapshot} on the <em>current</em> path.</li>
     *   <li>{@link GitService#reset(Vault)} on the <em>current</em> path — local
     *       history discarded, fresh empty repository.</li>
     *   <li>If {@code --path} differs: copy the (now Git-fresh) directory tree to
     *       the new path via {@code FileUtil.copyRecursively}, then remove the
     *       original only after the copy succeeds — never the reverse order.</li>
     *   <li>Only now are the {@link Vault}'s fields (owner, name, path, git.*)
     *       mutated — {@link GitService#reset} and the copy step both need the
     *       <em>original</em> path/identity to operate on the right location.</li>
     *   <li>{@link VaultService#update(Vault)} persists the new fields.</li>
     *   <li>{@link GitService#bootstrapVault(Vault)} writes the new authenticated
     *       remote URL — the freshly-reset repo has no remote configured yet, so
     *       this always resolves to {@code git remote add}, never {@code set-url}.</li>
     * </ol>
     * <p>Step 2 runs before step 3 deliberately: a failure between them leaves an
     * intact vault at the <em>original</em> location with reset history — never a
     * registered vault pointing at a non-repository path, nor data split across
     * two locations with the registry already pointing at the wrong one.</p>
     *
     * <p>Required flags: {@code --vault} only. Optional: {@code --owner},
     * {@code --name}, {@code --path}, {@code --git.*} (including
     * {@code --git.username}/{@code --git.token} — same fallback resolution as
     * {@link GitService#bootstrapVault(Vault)}: per-vault value if provided,
     * otherwise whatever is already registered or configured globally), and
     * {@code --force} to bypass the confirmation prompt.</p>
     *
     * <p>At least one of {@code --owner}/{@code --name}/{@code --path} must
     * actually differ from the vault's current values. If none do:</p>
     * <ul>
     *   <li>no {@code --git.*} flags either → nothing was requested at all,
     *       logged and treated as a no-op ({@code 2}).</li>
     *   <li>{@code --git.*} flags present → this is a misuse of {@code relocate}
     *       for a credential-only rotation, which does not require discarding
     *       Git history — rejected ({@code 1}), directing the user to
     *       {@code vault update} instead.</li>
     * </ul>
     *
     * @param flags         parsed CLI flags
     * @param vaults        the list of registered vaults
     * @param vaultService  vault persistence service
     * @param markerService marker claim/release service
     * @param gitService    Git operations service
     * @param logService    shared logging service
     * @return {@code 0} on success; {@code 1} on error (missing/unknown flags,
     *         vault not resolved, credential-only request with no structural
     *         change, snapshot/reset/copy/update/bootstrap failure); {@code 2}
     *         if nothing was requested at all, or if the user declines the
     *         confirmation prompt (both no-op)
     */
    public static int handleVaultRelocate(Map<String, String> flags, List<Vault> vaults,
                                          VaultService vaultService,
                                          MarkerService markerService,
                                          GitService gitService,
                                          LogService logService) {

        if (hasUnknownFlags(flags, FLAGS_VAULT_RELOCATE, "handleVaultRelocate", logService)) return 1;
        if (hasBlankRequiredFlags(flags, Set.of("vault"), "handleVaultRelocate", logService)) return 1;
        if (hasBlankOptionalValue(flags, Set.of("owner", "name", "path"), "handleVaultRelocate", logService)) return 1;

        Vault vault;
        try {
            vault = resolveVaultFlag(flags.get("vault"), vaults);
        } catch (VaultNotFoundException | VaultAmbiguousException e) {
            logService.error("handleVaultRelocate - " + e.getMessage());
            listRegistered(vaults, logService);
            return 1;
        }

        String newOwner = flags.getOrDefault("owner", vault.getOwner());
        String newName  = flags.getOrDefault("name", vault.getName());
        String newPath  = Path.of(flags.getOrDefault("path", vault.getPath()))
                .toAbsolutePath().normalize().toString();

        boolean structuralChange = !newOwner.equals(vault.getOwner())
                || !newName.equals(vault.getName())
                || !newPath.equals(vault.getPath());

        Map<String, String> gitFlags = new LinkedHashMap<>();
        flags.forEach((key, value) -> {
            if (key.startsWith("git.")) gitFlags.put(key, value);
        });

        if (!structuralChange) {
            if (gitFlags.isEmpty()) {
                logService.info("handleVaultRelocate: no changes requested.");
                return 2;
            } else {
                logService.error("handleVaultRelocate: no structural change requested "
                        + "(owner/name/path unchanged) - use 'vault update' to rotate "
                        + "credentials without resetting Git history");
                return 1;
            }
        }

        if (!newPath.equals(vault.getPath())) {
            try {
                markerService.checkNoNestingConflict(newPath);
            } catch (MarkerClaimException e) {
                logService.error("handleVaultRelocate - " + e.getMessage());
                return 1;
            }
        }

        if (!flags.containsKey(Main.FLAG_FORCE)) {
            System.out.print("This will PERMANENTLY discard local Git history for "
                    + vault.getRepoSlug() + ". Continue? (y/N): ");

            int response;
            try {
                response = System.in.read();
            } catch (IOException e) {
                logService.error("handleVaultRelocate - failed to read user input: " + e.getMessage(), e);
                return 1;
            }
            if (response != 'y' && response != 'Y') {
                logService.info("Aborted.");
                return 2;
            }
        }

        try {
            vaultService.makeVaultSnapshot(vault);
        } catch (VaultException | GitignoreException e) {
            logService.error("handleVaultRelocate - snapshot failed: " + e.getMessage(), e);
            return 1;
        }

        try {
            gitService.reset(vault);
        } catch (GitException e) {
            logService.error("handleVaultRelocate - reset failed: " + e.getMessage(), e);
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logService.error("handleVaultRelocate - interrupted: " + e.getMessage(), e);
            return 1;
        }

        if (!newPath.equals(vault.getPath())) {
            Path target = Path.of(newPath);
            try {
                FileUtil.copyRecursively(Path.of(vault.getPath()), target);
            } catch (IOException e) {
                logService.error("handleVaultRelocate - copy failed: " + e.getMessage(), e);
                return 1;
            }
            // The raw copy may have carried over the OLD .vault marker (if one existed at
            // the original location) — it must not occupy the new location's claim slot,
            // since vaultService.update() below will atomically claim a fresh marker there
            // via claimVaultPath. Remove it defensively; harmless no-op if none was copied.
            try {
                Files.deleteIfExists(target.resolve(".vault"));
            } catch (IOException e) {
                logService.warn("handleVaultRelocate - unable to remove copied marker at new path: "
                        + e.getMessage());
            }
            try {
                FileUtil.deleteRecursively(Path.of(vault.getPath()));
            } catch (IOException e) {
                logService.warn("handleVaultRelocate - old path not cleaned up: " + e.getMessage());
                // non-fatal: the copy already succeeded, proceed
            }
        }

        // Construct a fresh copy instead of mutating `vault` in place — `vault` is the
        // same reference held inside VaultService's internal map (via resolveVaultFlag),
        // and update() relies on being able to read its PREVIOUS state (via findById)
        // to detect that the path actually changed. Mutating in place would make that
        // detection always see "no change", silently skipping claim/release entirely.
        Vault updated = new Vault(vault.getId(), newOwner, newName, newPath,
                vault.getGitName(), vault.getGitEmail(), vault.getGitUsername(),
                vault.getGitToken(), vault.getGitBranch(), vault.getGitRemote());
        applyGitFlagsToVault(gitFlags, updated, logService);

        try {
            vaultService.update(updated);
        } catch (VaultException e) {
            logService.error("handleVaultRelocate - update failed: " + e.getMessage(), e);
            return 1;
        }

        try {
            gitService.bootstrapVault(updated);
        } catch (GitException e) {
            logService.error("handleVaultRelocate - bootstrap failed: " + e.getMessage(), e);
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logService.error("handleVaultRelocate - interrupted: " + e.getMessage(), e);
            return 1;
        }

        logService.info("Vault relocated: " + updated.getRepoSlug());
        return 0;
    }

    /**
     * Handles {@code vault list} — prints all registered vaults in tabular format.
     *
     * <p>No mandatory flags. Returns {@code 1} only on a defensive null check —
     * under normal operation the list is always non-null.</p>
     *
     * @param flags      parsed CLI flags
     * @param vaults     the list of registered vaults
     * @param logService shared logging service
     * @return {@code 0} on success, {@code 1} if the vault list is null or an
     *         unknown flag is present
     */
    public static int handleVaultList(Map<String, String> flags, List<Vault> vaults, LogService logService) {
        if (hasUnknownFlags(flags, FLAGS_VAULT_LIST, "handleVaultList", logService)) return 1;
        if (hasBlankOptionalValue(flags, Set.of(), "handleVaultList", logService)) return 1;

        if (vaults == null) {
            logService.error("handleVaultList: vault list is null");
            return 1;
        }
        if (vaults.isEmpty()) {
            logService.info("No vaults registered.");
            return 0;
        }
        logService.info("VAULT                    | PATH");
        logService.info("-".repeat(60));
        for (Vault vault : vaults) {
            logService.info(String.format("%-24s | %s", vault.getRepoSlug(), vault.getPath()));
        }
        logService.info("-".repeat(60));
        return 0;
    }

    /**
     * Handles {@code vault show} — prints full details of a single vault.
     *
     * <p>Mandatory fields (owner, name, path) are always shown. Per-vault Git
     * overrides are shown only when explicitly set on the vault, or always when
     * {@code --defaults} is present. The token is always masked as
     * {@code <hidden>}; absent fields print {@code (from config)}.</p>
     *
     * <p>Required flags: {@code --vault}.
     * Optional flags: {@code --defaults} — shows all git fields regardless of
     * whether they have been overridden at vault level.</p>
     *
     * @param flags      parsed CLI flags
     * @param vaults     the list of registered vaults
     * @param maxLines   maximum number of status lines to display
     * @param gitService Git operations service
     * @param logService shared logging service
     * @return {@code 0} on success, {@code 1} if the vault cannot be resolved
     */
    public static int handleVaultShow(Map<String, String> flags, List<Vault> vaults,
                                      int maxLines,
                                      GitService gitService,
                                      LogService logService) {
        if (hasUnknownFlags(flags, FLAGS_VAULT_SHOW, "handleVaultShow", logService)) return 1;
        if (hasBlankRequiredFlags(flags, Set.of("vault"), "handleVaultShow", logService)) return 1;
        if (hasBlankOptionalValue(flags, Set.of(), "handleVaultShow", logService)) return 1;

        Vault vault;
        try {
            vault = resolveVaultFlag(flags.get("vault"), vaults);
        } catch (VaultNotFoundException | VaultAmbiguousException e) {
            logService.error("handleVaultShow - " + e.getMessage());
            listRegistered(vaults, logService);
            return 1;
        }

        boolean showDefaults = flags.containsKey("defaults");

        // -- mandatory fields (always shown)
        logService.info("Vault:  " + vault.getRepoSlug());
        logService.info("Owner:  " + vault.getOwner());
        logService.info("Name:   " + vault.getName());
        logService.info("Path:   " + vault.getPath());

        // -- per-vault git overrides
        // Each field is printed if explicitly set on this vault, OR if --defaults is active.
        // Token is always masked — never logged in clear text.
        if (showDefaults || vault.getGitName() != null)
            logService.info("Git Name:     " + orDefault(vault.getGitName()));
        if (showDefaults || vault.getGitEmail() != null)
            logService.info("Git Email:    " + orDefault(vault.getGitEmail()));
        if (showDefaults || vault.getGitUsername() != null)
            logService.info("Git Username: " + orDefault(vault.getGitUsername()));
        if (showDefaults || vault.getGitToken() != null)
            logService.info("Git Token:    "
                    + (vault.getGitToken() != null ? "<hidden>" : "(from config)"));
        if (showDefaults || vault.getGitBranch() != null)
            logService.info("Git Branch:   " + orDefault(vault.getGitBranch()));
        if (showDefaults || vault.getGitRemote() != null)
            logService.info("Git Remote:   " + orDefault(vault.getGitRemote()));

        // -- live git status
        try {
            String status = gitService.statusShort(vault, maxLines);
            logService.info("Status: " + (status.isEmpty() ? "(clean)" : "\n" + status.trim()));
        } catch (GitException | InterruptedException e) {
            logService.warn("handleVaultShow - git status unavailable: " + e.getMessage());
        }

        return 0;
    }

    /**
     * Returns the value if non-null, or a placeholder indicating the field
     * falls back to {@code config.properties}.
     *
     * @param value the per-vault override value, or {@code null} if not set
     * @return the value, or {@code "(from config)"} if absent
     */
    public static String orDefault(String value) {
        return value != null ? value : "(from config)";
    }

    /**
     * Detects required flags that are either entirely absent from {@code flags} or
     * present with a blank value — both are treated as the same violation: the
     * caller did not supply a real value for a field that cannot be meaningfully
     * empty.
     *
     * <p>Intended for structural flags ({@code --vault}, {@code --owner},
     * {@code --name}, {@code --path}) that must always resolve to a real value.
     * Do <strong>not</strong> use this for {@code --git.*} flags — a blank
     * {@code --git.token} (for example) is a deliberately supported way to clear
     * a per-vault credential override, not an error.</p>
     *
     * <p>Each invalid key produces its own log line, using a known syntax hint
     * when available (e.g. {@code --vault=<name|owner/name>}) so the message
     * conveys the expected format, not just that the flag is missing.</p>
     *
     * @param flags       parsed CLI flags
     * @param requiredKeys the set of flag keys that must be present and non-blank
     * @param handler     handler name used as log prefix, e.g. {@code "handleVaultUpdate"}
     * @param logService  shared logging service
     * @return {@code true} if at least one required key is absent or blank,
     *         {@code false} if all are present with a real value
     */
    public static boolean hasBlankRequiredFlags(Map<String, String> flags, Set<String> requiredKeys,
                                                String handler, LogService logService) {
        List<String> invalid = requiredKeys.stream()
                .filter(k -> !flags.containsKey(k) || flags.get(k).isBlank())
                .sorted()
                .toList();
        if (invalid.isEmpty()) return false;
        invalid.forEach(k -> {
            String hint = FLAG_SYNTAX_HINTS.getOrDefault(k, "--" + k + "=<value>");
            logService.error(handler + ": requires " + hint);
        });
        return true;
    }

    /**
     * Detects structural flags that are present in {@code flags} but hold a
     * blank value — unlike {@link #hasBlankRequiredFlags}, absence is not a
     * violation here: these keys are legitimately optional (e.g. {@code --path}
     * on {@code vault update}, left out to mean "don't touch it"). Only
     * "provided but empty" is treated as user error, since a blank structural
     * value never has a meaningful interpretation.
     *
     * <p>{@code --workspacePath} is always implicitly checked in addition to
     * {@code structuralKeys}, regardless of what the caller passes — it is a
     * global flag present on every command, and a blank value
     * ({@code --workspacePath=}) is never valid on any of them. Callers do not
     * need to (and should not) include {@code "workspacePath"} in
     * {@code structuralKeys} themselves.</p>
     *
     * @param flags         parsed CLI flags
     * @param structuralKeys optional structural keys to check when present
     *                       (e.g. {@code owner}, {@code name}, {@code path});
     *                       may be empty if the handler has none of its own —
     *                       {@code --workspacePath} is still checked in that case
     * @param handler       handler name used as log prefix, e.g. {@code "handleVaultUpdate"}
     * @param logService    shared logging service
     * @return {@code true} if at least one checked key is present but blank,
     *         {@code false} otherwise
     */
    public static boolean hasBlankOptionalValue(Map<String, String> flags, Set<String> structuralKeys,
                                                String handler, LogService logService) {
        Set<String> keysToCheck = new java.util.HashSet<>(structuralKeys);
        keysToCheck.add("workspacePath");

        List<String> blank = keysToCheck.stream()
                .filter(flags::containsKey)
                .filter(k -> flags.get(k).isBlank())
                .sorted()
                .toList();
        if (blank.isEmpty()) return false;
        blank.forEach(k -> logService.error(handler + ": --" + k + " was provided but has no value"));
        return true;
    }

    /**
     * Known syntax hints for required flags whose expected format is not obvious
     * from the key name alone — used by {@link #hasBlankRequiredFlags} to produce
     * an actionable error message instead of a generic "cannot be blank".
     */
    public static final Map<String, String> FLAG_SYNTAX_HINTS = Map.of(
            "vault", "--vault=<name|owner/name>"
    );


    /**
     * Finds the closest known flag to an unrecognized one, by Levenshtein
     * distance — used to produce a "did you mean...?" hint. Returns empty if no
     * known flag is within {@link #FLAG_SUGGESTION_MAX_DISTANCE}, avoiding a
     * misleading suggestion for a flag that is simply unrelated (e.g. belongs to
     * a different command entirely) rather than a typo.
     *
     * @param unknownFlag the flag key that was not recognized
     * @param knownFlags  the set of valid flag keys for the current command
     * @return the nearest match within threshold, or empty if none qualifies
     */
    private static Optional<String> nearestKnownFlag(String unknownFlag, Set<String> knownFlags) {
        return knownFlags.stream()
                .map(known -> Map.entry(known, Main.levenshteinDistance(unknownFlag, known)))
                .filter(e -> e.getValue() <= FLAG_SUGGESTION_MAX_DISTANCE)
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    /**
     * Maximum edit distance for a "did you mean...?" suggestion to be shown.
     * Calibrated for short flag names (typically 4-15 characters): 2 catches
     * single-character typos (missing/swapped/doubled letter) without matching
     * genuinely unrelated flags that happen to share a few characters.
     */
    private static final int FLAG_SUGGESTION_MAX_DISTANCE = 2;
}