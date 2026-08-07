package io.aledep10.nomadsync.cli;

import io.aledep10.nomadsync.config.NomadPropertiesLoader;
import io.aledep10.nomadsync.config.PropertyValidatorRegistry;
import io.aledep10.nomadsync.exception.*;
import io.aledep10.nomadsync.marker.MarkerType;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.service.MarkerService;
import io.aledep10.nomadsync.service.WorkspaceService;
import io.aledep10.nomadsync.util.ConfirmationUtil;
import io.aledep10.nomadsync.workspace.WorkspaceEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * CLI handlers for the {@code workspace} subcommand family — twin of
 * {@link VaultCli}, same shape and conventions, no {@link
 * io.aledep10.nomadsync.service.GitService} (a workspace is not itself a Git
 * repository).
 *
 * <h2>{@code --path}, not {@code --workspacePath}</h2>
 * <p>{@code --workspacePath} is a <strong>global</strong> flag, stripped by
 * {@code Main} before any subcommand dispatch (it selects which workspace the
 * whole invocation runs within) — it never reaches a handler here. The
 * location value for {@code create}/{@code add}/{@code relocate} uses
 * {@code --path} instead, same name already used by the equivalent
 * {@code vault} subcommands, with no collision.</p>
 *
 * <h2>Known scope gaps in this version</h2>
 * <ul>
 *   <li>{@code use} requires an already-registered {@code --workspace=<name>}
 *       — {@link WorkspaceService#use(String)} has no logic to auto-register
 *       an unregistered ("ghost") path under a name, so promoting one via a
 *       bare {@code --path} is not supported here.</li>
 *   <li>No {@code show} — not part of the seven subcommands decided for this
 *       iteration; a workspace only has {@code workspaceName}/{@code path} to
 *       show, already visible in {@code list}.</li>
 * </ul>
 */
public class WorkspaceCli extends AbstractCli {

    public static final String COMMAND = "workspace";
    public static final String DEFAULT_SUBCOMMAND = "list";
    public static final String FLAG_WORKSPACE = "workspace";
    public static final String FLAG_WORKSPACE_NAME = "workspaceName";
    public static final String FLAG_MAX_NESTING_DEPTH = "marker.maxNestingDepth";

    private final NomadPropertiesLoader loader;
    private final WorkspaceService workspaceService;
    private final MarkerService markerService;

    public WorkspaceCli(NomadPropertiesLoader loader,
            WorkspaceService workspaceService, MarkerService markerService, LogService logService) {
        super(logService);
        this.loader = loader;
        this.workspaceService = workspaceService;
        this.markerService = markerService;
    }

    @Override
    protected Map<String, String> syntaxHints() {
        return Map.of(FLAG_WORKSPACE, "--" + FLAG_WORKSPACE + "=<name>");
    }

    /**
     * Calibrated for workspace flag names (typically 4-15 characters, e.g.
     * {@code workspaceName}, {@code path}): same threshold as {@link VaultCli},
     * kept independent rather than shared so either can be recalibrated later
     * without affecting the other.
     */
    @Override
    protected int flagSuggestionMaxDistance() {
        return 2;
    }

    public int execute(String subcommand, Map<String, String> flags) {
        return switch (subcommand) {
            case "create"   -> handleWorkspaceCreate(flags);
            case "add"      -> handleWorkspaceAdd(flags);
            case "update"   -> handleWorkspaceUpdate(flags);
            case "relocate" -> handleWorkspaceRelocate(flags);
            case "remove"   -> handleWorkspaceRemove(flags);
            case "erase"    -> handleWorkspaceErase(flags);
            case "use"      -> handleWorkspaceUse(flags);
            case "list"     -> handleWorkspaceList(flags);
            case "show" -> handleWorkspaceShow(flags);
            default -> {
                logService.error("Unknown workspace subcommand: " + subcommand +
                        ". Use: create | add | rename | relocate | remove | erase | use | list");
                yield 1;
            }
        };
    }

    // ── Workspace CLI handlers ──────────────────────────────────────────────

    // Allowed flags per workspace subcommand.
    // "sub" is injected internally by the parser and is always implicitly allowed.
    // Global flags (workspacePath, vault, daemon) are removed from the map before
    // these handlers are invoked, so they must not appear here.
    private static final Set<String> FLAGS_WORKSPACE_CREATE = Set.of(
            FLAG_WORKSPACE_NAME, FLAG_PATH,
            FLAG_GIT_NAME, FLAG_GIT_EMAIL, FLAG_GIT_USERNAME, FLAG_GIT_TOKEN, FLAG_GIT_BRANCH, FLAG_GIT_REMOTE,
            FLAG_MAX_NESTING_DEPTH);
    // Allowed flags — same set as FLAGS_WORKSPACE_CREATE (workspaceName, path).
    private static final Set<String> FLAGS_WORKSPACE_ADD      = FLAGS_WORKSPACE_CREATE;
    private static final Set<String> FLAGS_WORKSPACE_UPDATE = Set.of(
            FLAG_WORKSPACE, FLAG_WORKSPACE_NAME,
            FLAG_GIT_NAME, FLAG_GIT_EMAIL, FLAG_GIT_USERNAME, FLAG_GIT_TOKEN, FLAG_GIT_BRANCH, FLAG_GIT_REMOTE,
            FLAG_MAX_NESTING_DEPTH);
    private static final Set<String> FLAGS_WORKSPACE_RELOCATE = Set.of(FLAG_WORKSPACE, FLAG_PATH, FLAG_FORCE);
    private static final Set<String> FLAGS_WORKSPACE_REMOVE   = Set.of(FLAG_WORKSPACE, FLAG_FORCE);
    private static final Set<String> FLAGS_WORKSPACE_ERASE    = Set.of(FLAG_WORKSPACE, FLAG_FORCE);
    private static final Set<String> FLAGS_WORKSPACE_USE      = Set.of(FLAG_WORKSPACE, FLAG_PATH);
    private static final Set<String> FLAGS_WORKSPACE_LIST     = Set.of();
    private static final Set<String> FLAGS_WORKSPACE_SHOW     = Set.of(FLAG_WORKSPACE);

    /**
     * Handles {@code workspace create} — bootstraps a brand-new workspace
     * directory (and its {@code .nomadsync-workspace} marker), registers it,
     * and scaffolds {@code config.properties} with any {@code git.*}/
     * {@code marker.maxNestingDepth} values provided at creation time — same
     * one-shot composition already used by {@code vault create}
     * ({@code create()} then apply-and-persist), not a two-step create-then-update.
     *
     * <p>Required flags: {@code --workspaceName}, {@code --path}. Optional:
     * {@code --git.*} (name/email/username/token/branch/remote),
     * {@code --marker.maxNestingDepth} (integer, {@code NomadSync-WSP-00X} —
     * governs {@code checkNoNestingConflict}'s descendant scan for every vault
     * created inside this workspace).</p>
     *
     * @param flags parsed CLI flags
     * @return {@code 0} on success; {@code 1} on error (missing/unknown flags,
     *         duplicate name, non-integer {@code --marker.maxNestingDepth},
     *         directory/marker creation failure, or {@code config.properties}
     *         scaffolding failure)
     */
    int handleWorkspaceCreate(Map<String, String> flags) {
        if (hasUnknownFlags(flags, FLAGS_WORKSPACE_CREATE, "handleWorkspaceCreate")) return 1;
        if (hasBlankRequiredFlags(flags, Set.of(FLAG_WORKSPACE_NAME, FLAG_PATH), "handleWorkspaceCreate")) return 1;
        if (hasBlankOptionalValue(flags, Set.of(FLAG_MAX_NESTING_DEPTH), "handleWorkspaceCreate")) return 1;

        Map<String, String> initialValues = new LinkedHashMap<>(extractGitFlags(flags));
        if (flags.containsKey(FLAG_MAX_NESTING_DEPTH)) {
            initialValues.put(FLAG_MAX_NESTING_DEPTH, flags.get(FLAG_MAX_NESTING_DEPTH));
        }
        Optional<String> validationError = PropertyValidatorRegistry.validateAll(initialValues);
        if (validationError.isPresent()) {
            logService.error("handleWorkspaceCreate: " + validationError.get());
            return 1;
        }

        String workspaceName = flags.get(FLAG_WORKSPACE_NAME);
        String path = flags.get(FLAG_PATH);

        WorkspaceEntry created;
        try {
            created = workspaceService.create(workspaceName, path);
        } catch (WorkspaceException e) {
            logService.error("handleWorkspaceCreate - " + e.getMessage(), e);
            return 1;
        }

        try {
            loader.createWorkspaceProperties(
                    Path.of(created.getPath()).resolve(MarkerType.WORKSPACE.folderName()), initialValues);
        } catch (ConfigException e) {
            logService.error("handleWorkspaceCreate - workspace registered but config.properties "
                    + "could not be scaffolded - " + e.getMessage(), e);
            return 1;
        }

        logService.info("Workspace created: " + created.getWorkspaceName());
        return 0;
    }

    /**
     * Handles {@code workspace add} — registers an already-existing workspace
     * directory (marker already present on disk, e.g. synced from another
     * machine). Deliberately does not write {@code config.properties} — unlike
     * {@code workspace create}, the directory already exists and its
     * configuration (if any) may already hold real values that must not be
     * silently overwritten. {@code --git.*}/{@code --marker.maxNestingDepth} are
     * rejected explicitly here, not merely unsupported by omission — use
     * {@code workspace update} afterward to set them.
     *
     * <p>Required flags: {@code --workspaceName}, {@code --path}.</p>
     *
     * @param flags parsed CLI flags
     * @return {@code 0} on success; {@code 1} on error (missing/unknown flags,
     *         a rejected config flag, duplicate name, missing/invalid marker at
     *         {@code --path})
     */
    int handleWorkspaceAdd(Map<String, String> flags) {
        Set<String> configFlags = new LinkedHashSet<>(extractGitFlags(flags).keySet());
        if (flags.containsKey(FLAG_MAX_NESTING_DEPTH)) configFlags.add(FLAG_MAX_NESTING_DEPTH);
        if (!configFlags.isEmpty()) {
            logService.error("handleWorkspaceAdd: --" + String.join(", --", configFlags) + " not accepted here. "
                    + "'workspace add' registers an existing workspace as-is - its config.properties (if any) "
                    + "already reflects real values and must not be silently overwritten. "
                    + "Use 'workspace update' after adding to change them.");
            return 1;
        }

        if (hasUnknownFlags(flags, FLAGS_WORKSPACE_ADD, "handleWorkspaceAdd")) return 1;
        if (hasBlankRequiredFlags(flags, Set.of(FLAG_WORKSPACE_NAME, FLAG_PATH), "handleWorkspaceAdd")) return 1;

        String workspaceName = flags.get(FLAG_WORKSPACE_NAME);
        String path = flags.get(FLAG_PATH);
        try {
            WorkspaceEntry added = workspaceService.add(workspaceName, path);
            logService.info("Workspace added: " + added.getWorkspaceName());
            return 0;
        } catch (WorkspaceException e) {
            logService.error("handleWorkspaceAdd - " + e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Handles {@code workspace update} — renames a registered entry
     * ({@code --workspaceName}) and/or updates its {@code config.properties}
     * ({@code --git.*}, {@code --marker.maxNestingDepth}). Replaces
     * {@code workspace rename}: renaming was the only editable field when that
     * name was chosen, no longer true now that workspace-level configuration
     * exists — same parity already established for {@code vault update}.
     *
     * <p>Does not touch {@code path} — a workspace's physical location changes
     * only through {@code workspace relocate}, same separation of concerns as
     * {@code vault update} rejecting {@code --path} ({@code NomadSync-VLT-016}).</p>
     *
     * <p>At least one optional flag must be provided — with none, the command is
     * a no-op and returns {@code 2}.</p>
     *
     * <p>Required flags: {@code --workspace}. Optional: {@code --workspaceName},
     * {@code --git.*} (name/email/username/token/branch/remote),
     * {@code --marker.maxNestingDepth} (integer).</p>
     *
     * @param flags parsed CLI flags
     * @return {@code 0} on success; {@code 1} on error (missing/unknown flags,
     *         non-integer {@code --marker.maxNestingDepth}, unresolved
     *         {@code --workspace}, name collision, or {@code config.properties}
     *         update failure); {@code 2} if no changes were requested (no-op)
     */
    int handleWorkspaceUpdate(Map<String, String> flags) {
        if (flags.containsKey(FLAG_PATH)) {
            logService.error("handleWorkspaceUpdate: --path changes are not supported here. "
                    + "If the workspace is still at its current registered location and you want NomadSync to "
                    + "physically move it, use 'workspace relocate' instead. "
                    + "If you already moved the files yourself and only need the registry realigned, "
                    + "use 'workspace remove --workspace=" + flags.get(FLAG_WORKSPACE) + " --force' "
                    + "followed by 'workspace add' at the new location.");
            return 1;
        }
        if (hasUnknownFlags(flags, FLAGS_WORKSPACE_UPDATE, "handleWorkspaceUpdate")) return 1;
        if (hasBlankRequiredFlags(flags, Set.of(FLAG_WORKSPACE), "handleWorkspaceUpdate")) return 1;
        if (hasBlankOptionalValue(flags, Set.of(FLAG_WORKSPACE_NAME, FLAG_MAX_NESTING_DEPTH), "handleWorkspaceUpdate"))
            return 1;

        String workspaceName = flags.get(FLAG_WORKSPACE);
        Map<String, String> configUpdates = new LinkedHashMap<>(extractGitFlags(flags));
        if (flags.containsKey(FLAG_MAX_NESTING_DEPTH)) {
            configUpdates.put(FLAG_MAX_NESTING_DEPTH, flags.get(FLAG_MAX_NESTING_DEPTH));
        }
        Optional<String> validationError = PropertyValidatorRegistry.validateAll(configUpdates);
        if (validationError.isPresent()) {
            logService.error("handleWorkspaceUpdate: " + validationError.get());
            return 1;
        }

        boolean renaming = flags.containsKey(FLAG_WORKSPACE_NAME);
        if (!renaming && configUpdates.isEmpty()) {
            logService.info("handleWorkspaceUpdate: no changes requested.");
            return 2;
        }

        WorkspaceEntry target;
        if (renaming) {
            try {
                target = workspaceService.rename(workspaceName, flags.get(FLAG_WORKSPACE_NAME));
            } catch (WorkspaceException e) {
                logService.error("handleWorkspaceUpdate - " + e.getMessage(), e);
                return 1;
            }
        } else {
            target = workspaceService.findByName(workspaceName).orElse(null);
            if (target == null) {
                logService.error("handleWorkspaceUpdate - workspace '" + workspaceName + "' not found");
                return 1;
            }
        }

        if (!configUpdates.isEmpty()) {
            try {
                loader.updateWorkspaceProperties(
                        Path.of(target.getPath()).resolve(MarkerType.WORKSPACE.folderName()), configUpdates);
            } catch (ConfigException e) {
                logService.error("handleWorkspaceUpdate - " + (renaming ? "renamed but " : "")
                        + "config.properties could not be updated - " + e.getMessage(), e);
                return 1;
            }
        }

        logService.info("Workspace updated: " + target.getWorkspaceName());
        return 0;
    }

    /**
     * Handles {@code workspace relocate} — physically moves a registered
     * workspace's directory (and every vault path it contains, rebased) to a
     * new location.
     *
     * <p>Destructive — physically moves files. Interactive {@code y/N}
     * confirmation is required unless {@code --force} is present, same bypass
     * semantics as {@code vault relocate}.</p>
     *
     * <p>Required flags: {@code --workspace}, {@code --path} (the new
     * location). Optional: {@code --force}.</p>
     *
     * @param flags parsed CLI flags
     * @return {@code 0} on success; {@code 1} on error (missing/unknown flags,
     *         unresolved {@code --workspace}, nesting conflict, cross-drive
     *         target, move/rebase failure); {@code 2} if nothing was requested
     *         at all (target path equals the current one), or if the user
     *         declines the confirmation prompt (both no-op)
     */
    int handleWorkspaceRelocate(Map<String, String> flags) {
        if (hasUnknownFlags(flags, FLAGS_WORKSPACE_RELOCATE, "handleWorkspaceRelocate")) return 1;
        if (hasBlankRequiredFlags(flags, Set.of(FLAG_WORKSPACE, FLAG_PATH), "handleWorkspaceRelocate")) return 1;

        String workspaceName = flags.get(FLAG_WORKSPACE);
        String newPath = Path.of(flags.get(FLAG_PATH)).toAbsolutePath().normalize().toString();

        Optional<WorkspaceEntry> existing = workspaceService.findByName(workspaceName);
        if (existing.isEmpty()) {
            logService.error("handleWorkspaceRelocate - workspace '" + workspaceName + "' not found");
            return 1;
        }

        if (newPath.equals(existing.get().getPath())) {
            logService.info("handleWorkspaceRelocate: no changes requested.");
            return 2;
        }

        try {
            markerService.checkNoNestingConflict(newPath, MarkerType.WORKSPACE);
        } catch (MarkerClaimException e) {
            logService.error("handleWorkspaceRelocate - " + e.getMessage());
            return 1;
        }

        try {
            if (isCrossDrive(Path.of(existing.get().getPath()), Path.of(newPath))) {
                logService.error("handleWorkspaceRelocate - relocating across a different drive/filesystem "
                        + "is not supported in this version - move the directory with a system tool, "
                        + "then use 'workspace add' at the new location");
                return 1;
            }
        } catch (IOException e) {
            logService.error("handleWorkspaceRelocate - unable to determine filesystem for '" + newPath
                    + "': " + e.getMessage(), e);
            return 1;
        }

        switch (ConfirmationUtil.confirm("This will move the workspace directory to '" + newPath +
                        "'. Continue? (y/N): ", flags.containsKey(FLAG_FORCE), logService)) {
            case DECLINED -> {
                logService.info("Aborted.");
                return 2;
            }
            case INPUT_ERROR -> {
                return 1;   // already logged by confirm()
            }
            case CONFIRMED -> { /* fall through, proceed */ }
        }

        try {
            WorkspaceEntry relocated = workspaceService.relocate(workspaceName, newPath);
            logService.info("Workspace relocated: " + relocated.getWorkspaceName() + " -> " + relocated.getPath());
            return 0;
        } catch (WorkspaceException e) {
            logService.error("handleWorkspaceRelocate - " + e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Handles {@code workspace remove} — unregisters a workspace entry,
     * never touches the local directory (see {@link #handleWorkspaceErase}
     * for physical deletion).
     *
     * <p>Interactive {@code y/N} confirmation is required unless
     * {@code --force} is present — same convention as {@code vault remove},
     * even though this operation is not itself destructive to files: it is
     * still an irreversible registry change.</p>
     *
     * <p>Required flags: {@code --workspace}. Optional: {@code --force}.</p>
     *
     * @param flags parsed CLI flags
     * @return {@code 0} on success; {@code 1} on error (missing/unknown flags,
     *         unresolved {@code --workspace}, target is the current default);
     *         {@code 2} if the user declines the confirmation prompt (no-op)
     */
    int handleWorkspaceRemove(Map<String, String> flags) {
        if (hasUnknownFlags(flags, FLAGS_WORKSPACE_REMOVE, "handleWorkspaceRemove")) return 1;
        if (hasBlankRequiredFlags(flags, Set.of(FLAG_WORKSPACE), "handleWorkspaceRemove")) return 1;

        String workspaceName = flags.get(FLAG_WORKSPACE);

        switch (ConfirmationUtil.confirm("Remove workspace '" + workspaceName +
                        "' from the registry? (y/N): ", flags.containsKey(FLAG_FORCE), logService)) {
            case DECLINED -> {
                logService.info("Aborted.");
                return 2;
            }
            case INPUT_ERROR -> {
                return 1;   // already logged by confirm()
            }
            case CONFIRMED -> { /* fall through, proceed */ }
        }

        try {
            workspaceService.remove(workspaceName);
            logService.info("Workspace removed: " + workspaceName);
            return 0;
        } catch (WorkspaceException e) {
            logService.error("handleWorkspaceRemove - " + e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Handles {@code workspace erase} — unregisters a workspace entry and
     * physically deletes its directory. No pre-destructive backup
     * ({@code NomadSync-WSP-003}) — accepted risk, a workspace holds no
     * versioned content of value.
     *
     * <p>Interactive {@code y/N} confirmation is required unless
     * {@code --force} is present.</p>
     *
     * <p>Required flags: {@code --workspace}. Optional: {@code --force}.</p>
     *
     * @param flags parsed CLI flags
     * @return {@code 0} on success; {@code 1} on error (missing/unknown flags,
     *         unresolved {@code --workspace}, target is the current default,
     *         deletion failure); {@code 2} if the user declines the
     *         confirmation prompt (no-op)
     */
    int handleWorkspaceErase(Map<String, String> flags) {
        if (hasUnknownFlags(flags, FLAGS_WORKSPACE_ERASE, "handleWorkspaceErase")) return 1;
        if (hasBlankRequiredFlags(flags, Set.of(FLAG_WORKSPACE), "handleWorkspaceErase")) return 1;

        String workspaceName = flags.get(FLAG_WORKSPACE);

        switch (ConfirmationUtil.confirm("This will PERMANENTLY delete the workspace directory for '"
                        + workspaceName + "'. Continue? (y/N): ", flags.containsKey(FLAG_FORCE), logService)) {
            case DECLINED -> {
                logService.info("Aborted.");
                return 2;
            }
            case INPUT_ERROR -> {
                return 1;   // already logged by confirm()
            }
            case CONFIRMED -> { /* fall through, proceed */ }
        }

        try {
            workspaceService.erase(workspaceName);
            logService.info("Workspace erased: " + workspaceName);
            return 0;
        } catch (WorkspaceException e) {
            logService.error("handleWorkspaceErase - " + e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Handles {@code workspace use} — reassigns which registered entry is
     * the default.
     *
     * <p>Required flags: {@code --workspace} — must already be registered
     * (see class-level Javadoc on the known scope gap: no ghost-path
     * promotion in this version).</p>
     *
     * @param flags parsed CLI flags
     * @return {@code 0} on success; {@code 1} on error (missing/unknown
     *         flags, unresolved {@code --workspace})
     */
    int handleWorkspaceUse(Map<String, String> flags) {
        if (hasUnknownFlags(flags, FLAGS_WORKSPACE_USE, "handleWorkspaceUse")) return 1;
        if (hasBlankOptionalValue(flags, Set.of(FLAG_PATH), "handleWorkspaceUse")) return 1;

        String workspaceName = flags.get(FLAG_WORKSPACE);
        if (workspaceName == null || workspaceName.isBlank()) {
            if (flags.containsKey(FLAG_PATH)) {
                logService.error("handleWorkspaceUse: a bare --path cannot be promoted directly - "
                        + "register it first with 'workspace add --workspaceName=<name> --path=" + flags.get(FLAG_PATH)
                        + "', then 'workspace use --workspace=<name>'");
            } else {
                logService.error("handleWorkspaceUse: requires --workspace=<name>");
            }
            return 1;
        }

        try {
            WorkspaceEntry promoted = workspaceService.use(workspaceName);
            logService.info("Default workspace set: " + promoted.getWorkspaceName());
            return 0;
        } catch (WorkspaceException e) {
            logService.error("handleWorkspaceUse - " + e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Handles {@code workspace list} — prints every registered workspace in
     * tabular format, marking the current default.
     *
     * <p>No mandatory flags.</p>
     *
     * @param flags parsed CLI flags
     * @return {@code 0} on success, {@code 1} on an unknown flag
     */
    int handleWorkspaceList(Map<String, String> flags) {
        if (hasUnknownFlags(flags, FLAGS_WORKSPACE_LIST, "handleWorkspaceList")) return 1;

        List<WorkspaceEntry> workspaces = workspaceService.findAll();
        if (workspaces.isEmpty()) {
            logService.info("No workspaces registered.");
            return 0;
        }
        logService.info("WORKSPACE                | DEFAULT | PATH");
        logService.info("-".repeat(60));
        for (WorkspaceEntry workspace : workspaces) {
            logService.info(String.format("%-24s | %-7s | %s",
                    workspace.getWorkspaceName(), workspace.isDefault() ? "yes" : "", workspace.getPath()));
        }
        logService.info("-".repeat(60));
        return 0;
    }

    /**
     * Handles {@code workspace show} — prints registry fields and the resolved
     * {@code config.properties} contents for a single workspace.
     *
     * <p>Unlike {@code vault show}, no {@code --defaults} flag — a workspace has
     * no per-entity/global split to disambiguate, only "is this key set in this
     * workspace's own file or not". Absent keys print {@code (not set)}, not
     * silently omitted — makes clear the workspace falls through to
     * {@code installConfig.properties} for that key.</p>
     *
     * <p>Required flags: {@code --workspace}.</p>
     *
     * @param flags parsed CLI flags
     * @return {@code 0} on success, {@code 1} if the workspace cannot be resolved
     */
    int handleWorkspaceShow(Map<String, String> flags) {
        if (hasUnknownFlags(flags, FLAGS_WORKSPACE_SHOW, "handleWorkspaceShow")) return 1;
        if (hasBlankRequiredFlags(flags, Set.of(FLAG_WORKSPACE), "handleWorkspaceShow")) return 1;

        String workspaceName = flags.get(FLAG_WORKSPACE);
        WorkspaceEntry workspace = workspaceService.findByName(workspaceName).orElse(null);
        if (workspace == null) {
            logService.error("handleWorkspaceShow - workspace '" + workspaceName + "' not found");
            return 1;
        }

        logService.info("Workspace:  " + workspace.getWorkspaceName());
        logService.info("Path:       " + workspace.getPath());
        logService.info("Default:    " + workspace.isDefault());

        Properties config = new Properties();
        Path configFile = Path.of(workspace.getPath()).resolve(MarkerType.WORKSPACE.folderName())
                .resolve(NomadPropertiesLoader.WORKSPACE_CONFIG_FILE_NAME);
        if (Files.exists(configFile)) {
            try (var in = Files.newInputStream(configFile)) {
                config.load(in);
            } catch (IOException e) {
                logService.warn("handleWorkspaceShow - unable to read config.properties: " + e.getMessage());
            }
        }

        for (String key : List.of(FLAG_GIT_NAME, FLAG_GIT_EMAIL, FLAG_GIT_USERNAME,
                FLAG_GIT_TOKEN, FLAG_GIT_BRANCH, FLAG_GIT_REMOTE, FLAG_MAX_NESTING_DEPTH)) {
            String value = config.getProperty(key);
            String printed = value == null ? "(not set)"
                    : FLAG_GIT_TOKEN.equals(key) ? "<hidden>" : value;
            logService.info(String.format("%-24s %s", key + ":", printed));
        }

        return 0;
    }
}
