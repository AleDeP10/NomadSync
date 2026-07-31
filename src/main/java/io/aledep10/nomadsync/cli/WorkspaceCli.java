package io.aledep10.nomadsync.cli;

import io.aledep10.nomadsync.exception.*;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.service.MarkerService;
import io.aledep10.nomadsync.service.WorkspaceService;
import io.aledep10.nomadsync.workspace.WorkspaceEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 *   <li>No {@code list}/{@code show} — not part of the seven subcommands
 *       decided for this iteration; there is currently no CLI-visible way to
 *       inspect the registry.</li>
 * </ul>
 */
public class WorkspaceCli extends AbstractCli {

    public static final String COMMAND = "workspace";
    public static final String FLAG_WORKSPACE = "workspace";
    public static final String FLAG_WORKSPACE_NAME = "workspaceName";
    public static final String FLAG_PATH = "path";

    private final WorkspaceService workspaceService;
    private final MarkerService markerService;

    public WorkspaceCli(WorkspaceService workspaceService, MarkerService markerService, LogService logService) {
        super(logService);
        this.workspaceService = workspaceService;
        this.markerService = markerService;
    }

    @Override
    protected Map<String, String> syntaxHints() {
        return Map.of(FLAG_WORKSPACE, "--workspace=<name>");
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
            case "rename"   -> handleWorkspaceRename(flags);
            case "relocate" -> handleWorkspaceRelocate(flags);
            case "remove"   -> handleWorkspaceRemove(flags);
            case "erase"    -> handleWorkspaceErase(flags);
            case "use"      -> handleWorkspaceUse(flags);
            case "list"     -> handleWorkspaceList(flags);
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
    private static final Set<String> FLAGS_WORKSPACE_CREATE = Set.of("workspaceName", "path");
    // Allowed flags — same set as FLAGS_WORKSPACE_CREATE (workspaceName, path).
    private static final Set<String> FLAGS_WORKSPACE_ADD      = FLAGS_WORKSPACE_CREATE;
    private static final Set<String> FLAGS_WORKSPACE_RENAME   = Set.of(FLAG_WORKSPACE, "workspaceName");
    private static final Set<String> FLAGS_WORKSPACE_RELOCATE = Set.of(FLAG_WORKSPACE, "path", FLAG_FORCE);
    private static final Set<String> FLAGS_WORKSPACE_REMOVE   = Set.of(FLAG_WORKSPACE, FLAG_FORCE);
    private static final Set<String> FLAGS_WORKSPACE_ERASE    = Set.of(FLAG_WORKSPACE, FLAG_FORCE);
    private static final Set<String> FLAGS_WORKSPACE_USE      = Set.of(FLAG_WORKSPACE, "path");
    private static final Set<String> FLAGS_WORKSPACE_LIST     = Set.of();

    /**
     * Handles {@code workspace create} — bootstraps a brand-new workspace
     * directory (and its {@code .nomadsync-workspace} marker) and registers it.
     *
     * <p>Required flags: {@code --workspaceName}, {@code --path}.</p>
     *
     * @param flags parsed CLI flags
     * @return {@code 0} on success; {@code 1} on error (missing/unknown flags,
     *         duplicate name, directory/marker creation failure)
     */
    int handleWorkspaceCreate(Map<String, String> flags) {
        if (hasUnknownFlags(flags, FLAGS_WORKSPACE_CREATE, "handleWorkspaceCreate")) return 1;
        if (hasBlankRequiredFlags(flags, Set.of("workspaceName", "path"), "handleWorkspaceCreate")) return 1;

        String workspaceName = flags.get("workspaceName");
        String path = flags.get("path");

        try {
            WorkspaceEntry created = workspaceService.create(workspaceName, path);
            logService.info("Workspace created: " + created.getWorkspaceName());
            return 0;
        } catch (WorkspaceException e) {
            logService.error("handleWorkspaceCreate - " + e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Handles {@code workspace add} — registers an already-existing workspace
     * directory (marker already present on disk, e.g. synced from another
     * machine).
     *
     * <p>Required flags: {@code --workspaceName}, {@code --path}.</p>
     *
     * @param flags parsed CLI flags
     * @return {@code 0} on success; {@code 1} on error (missing/unknown flags,
     *         duplicate name, missing/invalid marker at {@code --path})
     */
    int handleWorkspaceAdd(Map<String, String> flags) {
        if (hasUnknownFlags(flags, FLAGS_WORKSPACE_ADD, "handleWorkspaceAdd")) return 1;
        if (hasBlankRequiredFlags(flags, Set.of("workspaceName", "path"), "handleWorkspaceAdd")) return 1;

        String workspaceName = flags.get("workspaceName");
        String path = flags.get("path");

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
     * Handles {@code workspace rename} — changes a registered entry's
     * {@code workspaceName} only, not its {@code path} (see
     * {@link #handleWorkspaceRelocate} for that).
     *
     * <p>Required flags: {@code --workspace} (the existing name),
     * {@code --workspaceName} (the new name).</p>
     *
     * @param flags parsed CLI flags
     * @return {@code 0} on success; {@code 1} on error (missing/unknown flags,
     *         unresolved {@code --workspace}, collision with the new name)
     */
    int handleWorkspaceRename(Map<String, String> flags) {
        if (hasUnknownFlags(flags, FLAGS_WORKSPACE_RENAME, "handleWorkspaceRename")) return 1;
        if (hasBlankRequiredFlags(flags, Set.of(FLAG_WORKSPACE, "workspaceName"), "handleWorkspaceRename")) return 1;

        String workspaceName = flags.get(FLAG_WORKSPACE);
        String newWorkspaceName = flags.get("workspaceName");

        try {
            WorkspaceEntry renamed = workspaceService.rename(workspaceName, newWorkspaceName);
            logService.info("Workspace renamed: " + workspaceName + " -> " + renamed.getWorkspaceName());
            return 0;
        } catch (WorkspaceException e) {
            logService.error("handleWorkspaceRename - " + e.getMessage(), e);
            return 1;
        }
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
     *         unresolved {@code --workspace}, nesting conflict, move/rebase
     *         failure); {@code 2} if the user declines the confirmation
     *         prompt (no-op)
     */
    int handleWorkspaceRelocate(Map<String, String> flags) {
        if (hasUnknownFlags(flags, FLAGS_WORKSPACE_RELOCATE, "handleWorkspaceRelocate")) return 1;
        if (hasBlankRequiredFlags(flags, Set.of(FLAG_WORKSPACE, "path"), "handleWorkspaceRelocate")) return 1;

        String workspaceName = flags.get(FLAG_WORKSPACE);
        String newPath = Path.of(flags.get("path")).toAbsolutePath().normalize().toString();

        Optional<WorkspaceEntry> existing = workspaceService.findByName(workspaceName);
        if (existing.isEmpty()) {
            logService.error("handleWorkspaceRelocate - workspace '" + workspaceName + "' not found");
            return 1;
        }

        try {
            markerService.checkNoNestingConflict(newPath);
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

        if (!flags.containsKey(FLAG_FORCE)) {
            System.out.print("This will move the workspace directory to '" + newPath + "'. Continue? (y/N): ");

            int response;
            try {
                response = System.in.read();
            } catch (IOException e) {
                logService.error("handleWorkspaceRelocate - failed to read user input: " + e.getMessage(), e);
                return 1;
            }
            if (response != 'y' && response != 'Y') {
                logService.info("Aborted.");
                return 2;
            }
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

        if (!flags.containsKey(FLAG_FORCE)) {
            System.out.print("Remove workspace '" + workspaceName + "' from the registry? (y/N): ");

            int response;
            try {
                response = System.in.read();
            } catch (IOException e) {
                logService.error("handleWorkspaceRemove - failed to read user input: " + e.getMessage(), e);
                return 1;
            }
            if (response != 'y' && response != 'Y') {
                logService.info("Aborted.");
                return 2;
            }
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

        if (!flags.containsKey(FLAG_FORCE)) {
            System.out.print("This will PERMANENTLY delete the workspace directory for '"
                    + workspaceName + "'. Continue? (y/N): ");

            int response;
            try {
                response = System.in.read();
            } catch (IOException e) {
                logService.error("handleWorkspaceErase - failed to read user input: " + e.getMessage(), e);
                return 1;
            }
            if (response != 'y' && response != 'Y') {
                logService.info("Aborted.");
                return 2;
            }
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
        if (hasBlankOptionalValue(flags, Set.of("path"), "handleWorkspaceUse")) return 1;

        String workspaceName = flags.get(FLAG_WORKSPACE);
        if (workspaceName == null || workspaceName.isBlank()) {
            if (flags.containsKey("path")) {
                logService.error("handleWorkspaceUse: a bare --path cannot be promoted directly - "
                        + "register it first with 'workspace add --workspaceName=<name> --path=" + flags.get("path")
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
     * @return {@code 0} on success, {@code 1} on an unknown/blank flag
     */
    int handleWorkspaceList(Map<String, String> flags) {
        if (hasUnknownFlags(flags, FLAGS_WORKSPACE_LIST, "handleWorkspaceList")) return 1;
        if (hasBlankOptionalValue(flags, Set.of(), "handleWorkspaceList")) return 1;

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
}
