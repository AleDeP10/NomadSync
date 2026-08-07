package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.exception.*;
import io.aledep10.nomadsync.marker.MarkerType;
import io.aledep10.nomadsync.marker.VaultMarker;
import io.aledep10.nomadsync.marker.WorkspaceMarker;
import io.aledep10.nomadsync.util.*;
import io.aledep10.nomadsync.vault.Vault;
import io.aledep10.nomadsync.workspace.WorkspaceEntry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the lifecycle of registered {@link WorkspaceEntry} instances — load,
 * persist, and CRUD. Mirrors {@link VaultService} in shape and conventions;
 * see that class's Javadoc for the rationale shared by both.
 *
 * <h2>Registry location — differs from {@code catalog.json}</h2>
 * <p>{@code workspaces.json} cannot live inside a workspace (it is the registry
 * that answers "which workspaces exist" — a workspace cannot be resolved before
 * this file is read). It lives next to the running JAR instead, resolved once
 * by {@code Main} and passed in as {@code installDir} — this service never
 * inspects {@code ProtectionDomain} itself, mirroring how {@link VaultService}
 * receives an already-resolved {@code configDir} rather than resolving it.</p>
 *
 * <h2>Identity — no UUID</h2>
 * <p>Unlike {@link io.aledep10.nomadsync.vault.Vault}, {@link WorkspaceEntry} has
 * no synthetic id — {@code workspaceName} alone is the unique identity
 * ({@code NomadSync-WSP-001}). The in-memory cache is therefore keyed by
 * {@code workspaceName} directly, not by a generated UUID.</p>
 *
 * <h2>"Exactly one default" invariant</h2>
 * <p>Enforced at every write, not just checked after the fact: {@link #load()}
 * rejects a file with zero or more than one entry marked {@code isDefault}
 * ({@link WorkspaceIntegrityException}); every mutating method that can affect
 * which entry is the default ({@link #create}, {@link #add}, {@link #use},
 * {@link #remove}, {@link #erase}) re-establishes the invariant before
 * returning.</p>
 *
 * <h2>First-entry-becomes-default rule</h2>
 * <p>Proposed for this grooming round, pending confirmation: the very first
 * entry ever registered (via {@link #create} or {@link #add}) on an empty
 * registry is automatically marked default. Every subsequent entry is not,
 * unless explicitly promoted via {@link #use}. Without this rule, {@code Main}'s
 * default-path resolution (no {@code --workspacePath} given) would have nothing
 * to return immediately after the very first {@code workspace create} — before
 * the user has had a chance to run {@code workspace use}.</p>
 *
 * <h2>No atomic write</h2>
 * <p>{@link #save()} overwrites {@code workspaces.json} directly via
 * {@link JsonMapper#saveWorkspacesToFile}, no temp-file/rename dance — same
 * accepted risk profile as {@link VaultService#save()}, which does the same
 * for {@code catalog.json} (the temp-file pattern in this codebase exists only
 * for {@code VaultService#saveConflict}, a distinct per-file operation).</p>
 *
 * <h2>No ambiguous-name resolution</h2>
 * <p>Unlike {@code Vault} (where a bare name can match several {@code owner}s),
 * {@code workspaceName} alone is already the unique identity — a bare-name
 * lookup can only ever match zero or one entry. No
 * {@code WorkspaceAmbiguousException} exists.</p>
 *
 * <h2>Constructor argument order</h2>
 * <p>Follows the project convention: domain-specific location first,
 * dependencies in descending order of complexity, {@link LogService} last.</p>
 */
public class WorkspaceService {

    private static final String REGISTRY_FILE_NAME = "workspaces.json";

    private final Map<String, WorkspaceEntry> workspaces = new LinkedHashMap<>();
    private final java.io.File registryFile;
    private final MarkerService markerService;
    private final LogService logService;

    /**
     * @param installDir    directory containing the running JAR (already resolved
     *                      by the caller — see class-level Javadoc) — base for
     *                      locating {@code workspaces.json}
     * @param markerService shared marker protection engine — handles all
     *                      {@code .nomadsync-workspace} claim/release/refresh
     *                      mechanics generically
     * @param logService    shared logging service
     */
    public WorkspaceService(Path installDir, MarkerService markerService, LogService logService) {
        this.markerService = markerService;
        this.logService = logService;
        this.registryFile = installDir.resolve(REGISTRY_FILE_NAME).toFile();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Loads all workspace entries from {@code workspaces.json} and replaces the
     * current in-memory state.
     *
     * <p>If the file does not exist, the in-memory state is cleared and an empty
     * list is returned — no exception is thrown.</p>
     *
     * <p>Two validations are run before the in-memory state is replaced:</p>
     * <ol>
     *   <li>No two entries share the same {@code workspaceName}.</li>
     *   <li>Exactly one entry has {@code isDefault() == true} — unless the file
     *       is empty, in which case the invariant is vacuously satisfied.</li>
     * </ol>
     *
     * @return the list of loaded workspace entries
     * @throws WorkspaceException if either validation fails, or if the file
     *                            exists but cannot be read or parsed
     */
    public List<WorkspaceEntry> load() throws WorkspaceException {
        logService.info("load - loading workspace registry from " + registryFile.getPath());
        if (!registryFile.exists()) {
            logService.warn("load - workspace registry not found at " + registryFile.getPath()
                    + " - default workspace resolution is unavailable until 'workspace create'/'workspace add' "
                    + "populates it (explicit --workspacePath still works)");
            return List.of();
        }

        List<WorkspaceEntry> loaded;
        try {
            loaded = JsonMapper.loadWorkspacesFromFile(registryFile);
        } catch (IOException e) {
            throw new WorkspaceParseException("Unable to parse the workspace registry: " + e.getMessage(), e);
        }

        validateUniqueWorkspaceNames(loaded);
        validateExactlyOneDefault(loaded);

        workspaces.clear();
        loaded.forEach(w -> workspaces.put(w.getWorkspaceName(), w));
        logService.debug("load - " + workspaces.size() + " workspace(s) loaded");

        String now = DateFormats.nowLog();
        for (WorkspaceEntry w : workspaces.values()) {
            WorkspaceMarker marker = WorkspaceMarker.create(stableMarkerId(w.getPath()), w.getWorkspaceName(), now);
            markerService.refresh(MarkerType.WORKSPACE, w.getPath(), marker);
        }

        return new ArrayList<>(workspaces.values());
    }

    private void validateUniqueWorkspaceNames(List<WorkspaceEntry> registry) throws WorkspaceIntegrityException {
        Set<String> uniqueWorkspaceNames = new HashSet<>();
        for (WorkspaceEntry workspace : registry) {
            if (uniqueWorkspaceNames.contains(workspace.getWorkspaceName())) {
                throw new WorkspaceIntegrityException("duplicated workspace name: " + workspace.getWorkspaceName());
            }
            uniqueWorkspaceNames.add(workspace.getWorkspaceName());
        }
    }

    private void validateExactlyOneDefault(List<WorkspaceEntry> registry) throws WorkspaceIntegrityException {
        if (registry.isEmpty()) return;

        Set<String> defaultWorkspaceNames = new TreeSet<>();
        for (WorkspaceEntry workspace : registry) {
            if (workspace.isDefault()) {
                defaultWorkspaceNames.add(workspace.getWorkspaceName());
            }
        }

        if (defaultWorkspaceNames.isEmpty()) {
            throw new WorkspaceIntegrityException("no default workspace found");
        }
        if (defaultWorkspaceNames.size() > 1) {
            throw new WorkspaceIntegrityException("multiple default workspace found - "
                    + String.join(", ", defaultWorkspaceNames));
        }
    }

    /**
     * Persists the current in-memory workspace state to {@code workspaces.json}.
     *
     * <p>Called automatically by all mutating operations. No temp-file/rename —
     * see class-level Javadoc on the accepted risk profile.</p>
     *
     * @throws WorkspaceException if the file cannot be written
     */
    public void save() throws WorkspaceException {
        logService.info("save - persisting " + workspaces.size() + " workspace(s) to " + registryFile.getPath());
        try {
            JsonMapper.saveWorkspacesToFile(registryFile, new ArrayList<>(workspaces.values()));
        } catch (IOException e) {
            throw new WorkspaceException("Failed to persist workspace registry: " + e.getMessage(), e);
        }
    }

    // ── Queries (no logging — pure) ────────────────────────────────────────────

    /**
     * Returns a defensive copy of every registered workspace entry.
     */
    public List<WorkspaceEntry> findAll() {
        return workspaces.values().stream().map(WorkspaceEntry::copy).collect(Collectors.toList());
    }

    /**
     * Returns the entry with the given {@code workspaceName}, if registered.
     */
    public Optional<WorkspaceEntry> findByName(String workspaceName) {
        return Optional.ofNullable(workspaces.get(workspaceName)).map(WorkspaceEntry::copy);
    }

    /**
     * Returns the current default entry, if the registry is non-empty.
     */
    public Optional<WorkspaceEntry> findDefault() {
        return workspaces.values().stream().filter(WorkspaceEntry::isDefault).findFirst().map(WorkspaceEntry::copy);
    }

    // ── CRUD ────────────────────────────────────────────────────────────────

    /**
     * Bootstraps a brand-new workspace: creates the directory (if absent) and
     * its {@code .nomadsync-workspace} marker, then registers it.
     *
     * @param workspaceName unique identifier — must not already be registered
     * @param path          filesystem path — normalized to absolute before any check
     * @return the created, registered {@link WorkspaceEntry}
     * @throws WorkspaceException on duplicate name, path conflict (via
     *                            {@link MarkerService#checkNoNestingConflict}),
     *                            or persistence failure
     */
    public WorkspaceEntry create(String workspaceName, String path) throws WorkspaceException {
        String absolutePath = Path.of(path).toAbsolutePath().normalize().toString();
        if (findByName(workspaceName).isPresent())
            throw new WorkspaceIntegrityException("duplicated workspaceName: " + workspaceName);

        try {
            Files.createDirectories(Path.of(absolutePath));   // no-op if existing
        } catch (IOException e) {
            throw new WorkspaceException("Unable to create directory: " + e.getMessage(), e);
        }

        boolean firstEver = workspaces.isEmpty();
        WorkspaceEntry workspace = new WorkspaceEntry(workspaceName, absolutePath, firstEver);

        claimWorkspaceMarker(workspace);

        workspaces.put(workspace.getWorkspaceName(), workspace);
        save();
        return workspace;
    }

    private void claimWorkspaceMarker(WorkspaceEntry workspace) throws WorkspaceException {
        try {
            markerService.claim(MarkerType.WORKSPACE, workspace.getPath(),
                    WorkspaceMarker.create(stableMarkerId(workspace.getPath()),
                            workspace.getWorkspaceName(), DateFormats.nowLog()));
        } catch (MarkerClaimException e) {
            throw new WorkspaceException("Unable to claim workspace marker: " + e.getMessage(), e);
        }
    }

    private static String stableMarkerId(String absolutePath) {
        return UUID.nameUUIDFromBytes(absolutePath.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Registers an already-existing workspace directory (marker already present
     * on disk, e.g. synced from another machine) — no directory or marker is
     * created here, unlike {@link #create}.
     *
     * @param workspaceName unique identifier — must not already be registered
     * @param path          filesystem path — must already contain a valid
     *                      {@code .nomadsync-workspace} marker
     * @return the registered {@link WorkspaceEntry}
     * @throws WorkspaceException on duplicate name, missing/invalid marker, or
     *                            persistence failure
     */
    public WorkspaceEntry add(String workspaceName, String path) throws WorkspaceException {
        String absolutePath = Path.of(path).toAbsolutePath().normalize().toString();
        if (findByName(workspaceName).isPresent())
            throw new WorkspaceIntegrityException("duplicated workspaceName: " + workspaceName);
        if (!Files.isDirectory(Path.of(absolutePath).resolve(MarkerType.WORKSPACE.folderName())))
            throw new WorkspaceException("not a valid NomadSync workspace (missing marker): " + absolutePath);

        boolean firstEver = workspaces.isEmpty();
        WorkspaceEntry workspace = new WorkspaceEntry(workspaceName, absolutePath, firstEver);
        markerService.refresh(MarkerType.WORKSPACE, absolutePath,
                WorkspaceMarker.create(stableMarkerId(absolutePath), workspaceName, DateFormats.nowLog()));

        workspaces.put(workspace.getWorkspaceName(), workspace);
        save();
        return workspace;
    }

    /**
     * Renames a registered workspace entry — {@code workspaceName} only, not
     * its path (see {@link #relocate} for that).
     *
     * @throws WorkspaceNotFoundException if {@code workspaceName} is not registered
     * @throws WorkspaceException         on collision with {@code newWorkspaceName},
     *                                    or persistence failure
     */
    public WorkspaceEntry rename(String workspaceName, String newWorkspaceName) throws WorkspaceException {
        WorkspaceEntry workspace = findByName(workspaceName)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceName));
        if (findByName(newWorkspaceName).isPresent())
            throw new WorkspaceIntegrityException("duplicated workspaceName: " + newWorkspaceName);

        WorkspaceEntry renamed = new WorkspaceEntry(newWorkspaceName, workspace.getPath(), workspace.isDefault());  // mai mutazione in-place (vedi NomadSync-VLT-013)
        workspaces.remove(workspaceName);
        workspaces.put(newWorkspaceName, renamed);
        save();
        return renamed;
    }

    /**
     * Relocates a registered workspace entry to a new path — physically moves
     * {@code .nomadsync-workspace/} (and everything inside it: the marker
     * descriptor, {@code catalog.json}, {@code backups/}, {@code conflicts/}) and
     * every nested vault directory together, then rebases every vault's absolute
     * path in {@code catalog.json} from the old prefix to the new one. Unlike
     * {@code vault relocate}, no Git history to reset — a workspace is not itself
     * a Git repository.
     *
     * @throws WorkspaceNotFoundException if {@code workspaceName} is not registered
     * @throws WorkspaceException         on nesting conflict at the new path,
     *                                    move failure, marker identity transfer
     *                                    failure, vault path rebase failure, or
     *                                    persistence failure
     */
    public WorkspaceEntry relocate(String workspaceName, String newPath) throws WorkspaceException {
        WorkspaceEntry workspace = findByName(workspaceName)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceName));
        String oldPath = workspace.getPath();
        String absoluteNewPath = Path.of(newPath).toAbsolutePath().normalize().toString();

        try {
            markerService.checkNoNestingConflict(absoluteNewPath, MarkerType.WORKSPACE);
        } catch (MarkerClaimException e) {
            throw new WorkspaceException("Unable to relocate workspace: " + e.getMessage(), e);
        }

        try {
            Files.move(Path.of(oldPath), Path.of(absoluteNewPath));
        } catch (FileSystemException e) {
            String reason = e.getReason() != null ? e.getReason()
                    : OsUtil.isWindows()
                    ? "the folder or a file inside it may be open in another program "
                    + "(Explorer, an editor, a terminal with that directory as working dir) - close it and retry"
                    : "the move failed for a filesystem-level reason not reported by the JDK - "
                    + "check permissions and that the target's parent directory exists";
            throw new WorkspaceException("Unable to move workspace directory from '" + oldPath
                    + "' to '" + absoluteNewPath + "': " + reason, e);
        } catch (IOException e) {
            throw new WorkspaceException("Unable to move workspace directory from '" + oldPath
                    + "' to '" + absoluteNewPath + "': " + e.getMessage(), e);
        }

        // The marker folder moved with the rest of the tree, but its descriptor
        // still carries the OLD path's derived id (stableMarkerId is path-based -
        // it necessarily changes on relocate). claim() would fail (folder already
        // exists); refresh() would refuse (sameClaimant never matches old vs new
        // id). overwrite() is the correct tool: an unconditional identity transfer,
        // not a claimant conflict.
        try {
            markerService.overwrite(MarkerType.WORKSPACE, absoluteNewPath,
                    WorkspaceMarker.create(stableMarkerId(absoluteNewPath), workspaceName, DateFormats.nowLog()));
        } catch (MarkerClaimException e) {
            throw new WorkspaceException("Workspace directory moved to '" + absoluteNewPath
                    + "' but its marker identity could not be updated: " + e.getMessage(), e);
        }

        try {
            rebaseVaultPaths(oldPath, absoluteNewPath);
        } catch (IOException e) {
            throw new WorkspaceException("Workspace directory moved to '" + absoluteNewPath
                    + "' but vault paths in catalog.json could not be rebased: " + e.getMessage(), e);
        }

        try {
            updateVaultMarkerWorkspacePaths(absoluteNewPath);
        } catch (IOException | MarkerClaimException e) {
            throw new WorkspaceException("Workspace directory moved to '" + absoluteNewPath
                    + "' and catalog.json rebased, but one or more vault markers could not be updated "
                    + "with the new workspace path: " + e.getMessage(), e);
        }

        WorkspaceEntry relocated = new WorkspaceEntry(workspace.getWorkspaceName(), absoluteNewPath, workspace.isDefault());
        workspaces.put(workspaceName, relocated);
        save();
        return relocated;
    }

    /**
     * Rewrites every vault's absolute {@code path} in the relocated workspace's
     * {@code catalog.json} — replaces the {@code oldWorkspacePath} prefix with
     * {@code newWorkspacePath}, leaving the relative suffix (and every other
     * field) untouched. A no-op if the workspace has no vaults yet (no
     * {@code catalog.json}, or an empty one).
     *
     * <p>Bypasses {@link VaultService} entirely — this is a mechanical prefix
     * rebase, not a business mutation: no repoSlug/path uniqueness check applies
     * (a uniform prefix substitution cannot introduce a new collision that didn't
     * already exist), and no {@code VaultService} instance is expected to be
     * live against this same {@code catalog.json} during a single-shot CLI
     * invocation (the daemon/CLI concurrency risk this would otherwise raise is
     * already out of scope for v1.0.0/M8).</p>
     *
     * <p>Never mutates a loaded {@link Vault} in place — always constructs a
     * replacement, same discipline as {@code NomadSync-VLT-013}, even though
     * these instances are freshly loaded and never shared with any live
     * {@code VaultService} cache (so the specific bug that discipline was
     * introduced for could not recur here either way).</p>
     */
    private void rebaseVaultPaths(String oldWorkspacePath, String newWorkspacePath) throws IOException {
        File catalogFile = Path.of(newWorkspacePath)
                .resolve(MarkerType.WORKSPACE.folderName())
                .resolve(VaultService.CATALOG_FILE_NAME)
                .toFile();

        List<Vault> vaults = JsonMapper.loadVaultsFromFile(catalogFile);
        if (vaults.isEmpty()) return;

        List<Vault> rebased = new ArrayList<>();
        for (Vault vault : vaults) {
            String rebasedPath = vault.getPath().startsWith(oldWorkspacePath)
                    ? newWorkspacePath + vault.getPath().substring(oldWorkspacePath.length())
                    : vault.getPath();
            rebased.add(new Vault(vault.getId(), vault.getOwner(), vault.getName(), rebasedPath,
                    vault.getGitName(), vault.getGitEmail(), vault.getGitUsername(), vault.getGitToken(),
                    vault.getGitBranch(), vault.getGitRemote()));
        }
        JsonMapper.saveVaultsToFile(catalogFile, rebased);
    }

    /**
     * After a relocate has moved the workspace tree and rebased every vault's
     * {@code path} in {@code catalog.json}, updates each vault's own
     * {@code .nomadsync-vault/descriptor.json} to carry the new workspace root —
     * otherwise that field is left pointing at the pre-relocate location
     * indefinitely, exactly the staleness this method exists to close.
     *
     * <p>Reads the freshly-rebased {@code catalog.json} at {@code newWorkspacePath}
     * (not the in-memory {@code workspaces} map, which holds workspace entries,
     * not vault ones) to know which vaults exist and their new paths.</p>
     *
     * @param newWorkspacePath the workspace's new root, already moved and rebased
     * @throws IOException          if {@code catalog.json} cannot be read
     * @throws MarkerClaimException if a vault's marker cannot be overwritten
     */
    private void updateVaultMarkerWorkspacePaths(String newWorkspacePath) throws IOException, MarkerClaimException {
        File catalogFile = Path.of(newWorkspacePath).resolve(MarkerType.WORKSPACE.folderName())
                .resolve(VaultService.CATALOG_FILE_NAME).toFile();
        List<Vault> vaults = JsonMapper.loadVaultsFromFile(catalogFile);

        for (Vault vault : vaults) {
            markerService.overwrite(MarkerType.VAULT, vault.getPath(),
                    VaultMarker.create(vault.getId(), vault.getRepoSlug(), newWorkspacePath, DateFormats.nowLog()));
        }
    }

    /**
     * Removes a registered workspace entry from the registry — never touches
     * the local directory (see {@link #erase} for physical deletion).
     *
     * @throws WorkspaceNotFoundException if {@code workspaceName} is not registered
     * @throws WorkspaceException         if {@code workspaceName} is the current
     *                                    default (must {@link #use} another
     *                                    workspace first), or persistence failure
     */
    public void remove(String workspaceName) throws WorkspaceException {
        WorkspaceEntry workspace = findByName(workspaceName).orElseThrow(() -> new WorkspaceNotFoundException(workspaceName));
        if (workspace.isDefault())
            throw new WorkspaceException("cannot remove the default workspace '" + workspaceName +
                    "' - use 'workspace use' on another workspace first");

        markerService.release(MarkerType.WORKSPACE, workspace.getPath());
        workspaces.remove(workspaceName);
        save();
    }

    /**
     * Removes a registered workspace entry and severs NomadSync's logical link
     * to its directory — releases every contained vault's {@code .nomadsync-vault}
     * marker, then the workspace's own {@code .nomadsync-workspace} marker.
     * Everything else on disk (vault content, any non-marker file) is left
     * untouched — {@code erase} un-claims, it does not delete user data.
     *
     * <p>No pre-destructive backup — none is needed: nothing user-facing is ever
     * removed by this operation, only the marker folders that made the directory
     * and its vaults recognizable to NomadSync in the first place.</p>
     *
     * @throws WorkspaceNotFoundException if {@code workspaceName} is not registered
     * @throws WorkspaceException         if {@code workspaceName} is the current
     *                                    default, if the workspace's catalog cannot
     *                                    be read, or if persistence fails
     */
    public void erase(String workspaceName) throws WorkspaceException {
        WorkspaceEntry workspace = findByName(workspaceName)
                 .orElseThrow(() -> new WorkspaceNotFoundException(workspaceName));
        if (workspace.isDefault())
            throw new WorkspaceException("cannot erase the default workspace '" + workspaceName
                    + "' - use 'workspace use' on another workspace first");

        // Read the catalog BEFORE releasing the workspace marker — catalog.json
        // lives inside .nomadsync-workspace/, about to be deleted.
        File catalogFile = Path.of(workspace.getPath())
                .resolve(MarkerType.WORKSPACE.folderName())
                .resolve(VaultService.CATALOG_FILE_NAME)
                .toFile();
        List<Vault> vaults;
        try {
            vaults = JsonMapper.loadVaultsFromFile(catalogFile);   // empty list if no catalog yet
        } catch (IOException e) {
            throw new WorkspaceException("Unable to read catalog for workspace '" + workspaceName
                    + "': " + e.getMessage(), e);
        }

        // Un-claim every contained vault before un-claiming the workspace itself —
        // inside-out order, mirrors how the marker hierarchy is nested on disk.
        for (Vault vault : vaults) {
            markerService.release(MarkerType.VAULT, vault.getPath());
        }
        markerService.release(MarkerType.WORKSPACE, workspace.getPath());

        workspaces.remove(workspaceName);
        save();
    }

    /**
     * Reassigns which registered entry is the default — an atomic toggle, not
     * a full CRUD mutation: sets {@code isDefault} on the target, clears it on
     * every other entry, in a single write.
     *
     * @throws WorkspaceNotFoundException if {@code workspaceName} is not registered
     * @throws WorkspaceException         on persistence failure
     */
    public WorkspaceEntry use(String workspaceName) throws WorkspaceException {
        WorkspaceEntry target = findByName(workspaceName)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceName));
        WorkspaceEntry promoted = new WorkspaceEntry(target.getWorkspaceName(), target.getPath(), true);
        workspaces.replaceAll((name, w) -> name.equals(workspaceName)
                ? promoted
                : (w.isDefault() ? new WorkspaceEntry(w.getWorkspaceName(), w.getPath(), false) : w));
        save();
        return promoted;
    }

    // ── Flag resolution (NomadSync-WSP-002) ────────────────────────────────────

    /**
     * Resolves the {@code --workspace}/{@code --workspacePath} flag pair to an
     * absolute path, applying the coexistence-with-consistency-check rule
     * ({@code NomadSync-WSP-002}).
     *
     * @param workspaceFlag     {@code --workspace} value, or {@code null}/blank if absent
     * @param workspacePathFlag {@code --workspacePath} value, or {@code null}/blank if absent
     * @return the resolved absolute path, or {@code null} if both flags are absent
     * (caller falls back to the default workspace)
     * @throws WorkspaceNotFoundException if {@code workspaceFlag} does not match
     *                                    any registered entry
     * @throws WorkspaceException         if both flags are present and resolve
     *                                    to different paths
     */
    public String resolveWorkspacePath(String workspaceFlag, String workspacePathFlag) throws WorkspaceException {
        boolean hasName = StringUtil.isNonBlank(workspaceFlag);
        boolean hasPath = StringUtil.isNonBlank(workspacePathFlag);
        if (!hasName && !hasPath) return null;

        String normalizedPathFlag = hasPath ? Path.of(workspacePathFlag).toAbsolutePath().normalize().toString() : null;

        if (!hasName) return normalizedPathFlag;   // --workspacePath only, ghost workspace

        WorkspaceEntry workspace = findByName(workspaceFlag)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceFlag));
        if (hasPath && !workspace.getPath().equals(normalizedPathFlag))
            throw new WorkspaceException("'--workspace=" + workspaceFlag + "' resolves to '" + workspace.getPath()
                    + "' but '--workspacePath=" + normalizedPathFlag + "' was also given - the two disagree");

        return workspace.getPath();
    }
}