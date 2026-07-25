package io.aledep10.nomadsync;

import io.aledep10.nomadsync.config.NomadProperties;
import io.aledep10.nomadsync.exception.*;
import io.aledep10.nomadsync.gitignore.exception.GitignoreException;
import io.aledep10.nomadsync.hook.LogNotificationHook;
import io.aledep10.nomadsync.hook.NotificationHook;
import io.aledep10.nomadsync.marker.MarkerType;
import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.orchestrator.SyncEvent;
import io.aledep10.nomadsync.orchestrator.SyncEventQueue;
import io.aledep10.nomadsync.orchestrator.SyncOrchestrator;
import io.aledep10.nomadsync.service.*;
import io.aledep10.nomadsync.util.*;
import io.aledep10.nomadsync.vault.Vault;
import io.aledep10.nomadsync.scheduler.AutosaveScheduler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Entry point for NomadSync.
 *
 * <h2>CLI syntax</h2>
 * <pre>
 *   java -jar NomadSync.jar &lt;command&gt; [subcommand] [--config=&lt;path&gt;] [--vault=&lt;name|owner/name&gt;] [--daemon] [flags...]
 * </pre>
 *
 * <h2>Daemon vs one-shot mode</h2>
 * <p>Without {@code --daemon}: the process terminates automatically once all
 * per-vault queues are empty (one-shot CLI mode — {@code pull}, {@code push},
 * {@code sync}, {@code commit}).</p>
 * <p>With {@code --daemon}: the process stays alive indefinitely, waiting for
 * events from the Tray or socket layer. The Tray passes {@code --daemon} at
 * startup.</p>
 *
 * <p>{@code command} is positional and always required. For {@code vault},
 * a subcommand is also positional and required as the second argument
 * (e.g. {@code vault add}). All remaining arguments are flags in
 * {@code --key=value} form, order-independent.</p>
 *
 * <h2>Commands</h2>
 * <ul>
 *   <li>{@code pull}     — stash → pull → stash pop. Broadcasts to all vaults if {@code --vault} is absent.</li>
 *   <li>{@code push}     — commit local → push. Broadcasts if {@code --vault} is absent.</li>
 *   <li>{@code sync}     — full bidirectional sync. Broadcasts if {@code --vault} is absent.</li>
 *   <li>{@code commit}   — opens editor, commits locally with user message.
 *       {@code --vault} is mandatory ({@link EventType#COMMIT_MANUAL}).</li>
 *   <li>{@code autosave} — no-op; the {@link AutosaveScheduler} handles periodic publishing.</li>
 *   <li>{@code status}   — prints {@code git status} output. Broadcasts if {@code --vault} is absent.</li>
 *   <li>{@code config}   — updates {@code config.properties} (global) or {@code catalog.json}
 *       (per-vault). Does not start orchestrators.</li>
 *   <li>{@code vault}    — manages registered vaults via subcommands:
 *       {@code create}, {@code add}, {@code update}, {@code remove}, {@code relocate}, {@code list}, {@code show}.</li>
 * </ul>
 *
 * <h2>Vault resolution ({@code --vault})</h2>
 * <ul>
 *   <li>{@code --vault=name} — unambiguous if exactly one vault has that name;
 *       error if zero or more than one match.</li>
 *   <li>{@code --vault=owner/name} — exact repoSlug match.</li>
 *   <li>absent — broadcast (for non-mandatory commands) or error (for mandatory).</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>Configuration is loaded from the filesystem at startup via
 * {@link java.io.FileInputStream} — path defaults to {@code ./config.properties}
 * and can be overridden with {@code --config=<path>}. All property keys are
 * declared as constants in {@link io.aledep10.nomadsync.config.NomadProperties}.
 * Built-in classpath defaults are available via
 * {@link io.aledep10.nomadsync.config.NomadPropertiesLoader} — the two sources
 * are complementary: classpath for defaults bundled in the JAR, filesystem for
 * the user's environment-specific overrides.</p>
 *
 * <h2>Startup sequence</h2>
 * <ol>
 *   <li>Parse CLI flags and load configuration.</li>
 *   <li>Bootstrap shared dependencies.</li>
 *   <li>Load registered vaults and resolve {@code --vault} if present.</li>
 *   <li>Handle early-exit commands ({@code vault}, {@code status}, {@code config})
 *       that do not require orchestrators.</li>
 *   <li>Bootstrap per-vault Git credentials via {@link GitService#bootstrapVault(Vault)}.</li>
 *   <li>Wire one {@link SyncOrchestrator} + {@link SyncEventQueue} per vault.</li>
 *   <li>Wire a broadcast queue and dispatcher thread.</li>
 *   <li>Configure the {@link AutosaveScheduler}.</li>
 *   <li>Register a shutdown hook to stop schedulers/orchestrators and flush logs.</li>
 *   <li>Translate the command into a typed {@link SyncEvent} and publish it.</li>
 *   <li>Start the autosave scheduler, broadcaster, and all orchestrators.</li>
 * </ol>
 *
 * <h2>Exit strategy</h2>
 * <p>All early-exit paths call {@link #exit(LogService, int)} instead of
 * {@link System#exit} directly. This ensures {@link LogService#close()} is
 * always invoked before the JVM terminates, giving asynchronous writers such as
 * {@link io.aledep10.nomadsync.logging.SeqHttpLogWriter} time to flush any
 * pending events before the daemon thread is killed.</p>
 */
public class Main {

    static final String CONFIG_FILE_NAME = "config.properties";
    static final String FLAG_FORCE = "force";

    public static void main(String[] args) {

        // ── 1. Parse command and flags ────────────────────────────────────────
        ParsedArgs parsed = parseArgs(args);
        if (parsed.isFailure()) {
            System.exit(parsed.errorExitCode());
            return;
        }
        String command = parsed.command();
        Map<String, String> flags = parsed.flags();

        String vaultFlag         = flags.get("vault");
        String workspacePathArg  = flags.get("workspacePath");
        boolean daemon           = flags.containsKey("daemon");

        flags.remove("workspacePath");
        flags.remove("daemon");

        // ── 2. Resolve and validate the workspace ─────────────────────────────
        Path workspacePath = resolveWorkspacePathOrExit(workspacePathArg);

        if (!Files.isDirectory(workspacePath.resolve(MarkerType.WORKSPACE.folderName()))) {
            System.err.println("Not a valid NomadSync workspace: " + workspacePath
                    + " (missing " + MarkerType.WORKSPACE.folderName() + ")");
            System.exit(1);
        }

        Path configDir  = workspacePath.resolve(MarkerType.WORKSPACE.folderName());
        Path configFile = configDir.resolve(CONFIG_FILE_NAME);

        Properties properties = new Properties();
        try (FileInputStream in = new FileInputStream(configFile.toFile())) {
            properties.load(in);
        } catch (IOException e) {
            System.err.println("Unable to load properties file: " + configFile);
            System.exit(1);
        }

        // ── 3. Bootstrap shared dependencies ──────────────────────────────────
        LogService       logService       = new LogService(properties, configDir);
        GitignoreService gitignoreService = new GitignoreService(logService);
        MarkerService    markerService    = new MarkerService(properties, logService);
        VaultService     vaultService     = new VaultService(configDir, markerService, gitignoreService, logService);
        GitService       gitService       = new GitService(properties, vaultService, gitignoreService, logService);
        NotificationHook hook             = new LogNotificationHook(logService);

        // ── 4. Load vaults ────────────────────────────────────────────────────
        final List<Vault> vaults = loadVaults(vaultService, logService);

        // ── 5. Early-exit commands — no orchestrators needed ──────────────────
        if ("vault".equals(command)) {
            // vault subcommands are allowed on an empty registry (e.g. vault add)
            String subcommand = flags.getOrDefault("sub", "list");
            int result = switch (subcommand) {
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
            exit(logService, result);
        }

        // For all other commands, an empty vault registry is a no-op.
        if (vaults.isEmpty()) {
            logService.warn("No vaults registered - nothing to do.");
            exit(logService, 0);
        }

        if ("status".equals(command)) {
            exit(logService, handleStatus(vaultFlag, vaults, gitService, logService));
        }

        if ("config".equals(command)) {
            exit(logService,
                    handleConfig(
                            flags, vaultFlag, vaults, vaultService, gitService, properties, configFile, logService));
        }

        // ── 6. Resolve --vault flag ───────────────────────────────────────────
        Vault targetVault;
        try {
            targetVault = resolveVaultFlag(vaultFlag, vaults);
        } catch (VaultNotFoundException | VaultAmbiguousException e) {
            logService.error("Main - " + e.getMessage());
            listRegistered(vaults, logService);
            exit(logService, 1);
            return;
        }

        // Validate mandatory-vault constraint
        EventType eventType = operationToEventType(command, logService);
        if (eventType != null && eventType.isMandatoryVault() && targetVault == null) {
            logService.error("command '" + command + "' requires --vault=<name|owner/name>");
            listRegistered(vaults, logService);
            exit(logService, 1);
            return;
        }

        // ── 7. Bootstrap per-vault Git credentials ────────────────────────────
        for (Vault vault : vaults) {
            try {
                gitService.bootstrapVault(vault);
            } catch (GitException | InterruptedException e) {
                logService.warn("bootstrapVault failed for " + vault.getRepoSlug()
                        + ": " + e.getMessage());
            }
        }

        // ── 8. Wire one queue + orchestrator per vault ─────────────────────────
        List<SyncEventQueue>   queues        = new ArrayList<>();
        List<SyncOrchestrator> orchestrators = new ArrayList<>();

        for (Vault vault : vaults) {
            LogService       vaultLog     = logService.withVault(vault.getRepoSlug());
            SyncEventQueue   queue        = new SyncEventQueue(vaultLog);
            SyncOrchestrator orchestrator = new SyncOrchestrator(vault, gitService, vaultLog, queue, hook);
            queues.add(queue);
            orchestrators.add(orchestrator);
        }

        // ── 9. Broadcast queue + dispatcher thread ────────────────────────────
        SyncEventQueue broadcastQueue = new SyncEventQueue(logService);
        Thread broadcaster = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    SyncEvent event = broadcastQueue.consume();
                    if (event.getVaultId() == null) {
                        queues.forEach(q -> q.publish(event));
                    } else {
                        int index = -1;
                        for (int i = 0; i < vaults.size(); i++) {
                            if (vaults.get(i).getId().equals(event.getVaultId())) {
                                index = i;
                                break;
                            }
                        }
                        if (index != -1) {
                            queues.get(index).publish(event);
                        } else {
                            logService.warn("Broadcaster: unknown vaultId "
                                    + event.getVaultId() + " - event discarded");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            logService.info("Broadcaster: stopped.");
        }, "nomadsync-broadcaster");

        // ── 10. AutosaveScheduler ─────────────────────────────────────────────
        long intervalMinutes = PropertiesUtil.getLong(
                properties, NomadProperties.Autosave.INTERVAL_MINUTES, 15L);
        AutosaveScheduler scheduler = new AutosaveScheduler(
                broadcastQueue, logService, intervalMinutes);

        // ── 11. Shutdown hook ─────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.stop();
            broadcaster.interrupt();
            orchestrators.forEach(SyncOrchestrator::stop);
            logService.close();
        }, "nomadsync-shutdown"));

        // ── 12. CLI → event ───────────────────────────────────────────────────
        switch (command) {
            case "pull" -> {
                SyncEvent event = new SyncEvent(EventType.PULL_LOGON,
                        targetVault != null ? targetVault.getId() : null);
                broadcastQueue.publish(event);
            }
            case "push" -> {
                SyncEvent event = new SyncEvent(EventType.PUSH_LOGOFF,
                        targetVault != null ? targetVault.getId() : null);
                broadcastQueue.publish(event);
            }
            case "sync" -> {
                SyncEvent event = new SyncEvent(EventType.SYNCHRONIZE,
                        targetVault != null ? targetVault.getId() : null);
                broadcastQueue.publish(event);
            }
            case "commit" -> {
                // targetVault guaranteed non-null (mandatory-vault check above)
                String message = openEditorForMessage(flags, properties, logService);
                if (message == null || message.isBlank()) {
                    logService.info("Empty commit message - aborting, no commit created.");
                    exit(logService, 0);
                    return;
                }
                broadcastQueue.publish(
                        new SyncEvent(EventType.COMMIT_MANUAL, targetVault.getId(), message));
            }
            case "autosave" -> { /* AutosaveScheduler handles periodic publishing */ }
            default -> {
                logService.error("Unknown command: " + command);
                exit(logService, 1);
                return;
            }
        }

        // ── 13. Start ─────────────────────────────────────────────────────────
        scheduler.start();
        broadcaster.start();

        List<Thread> threads = orchestrators.stream()
                .map(o -> new Thread(o::start,
                        "nomadsync-main-" + vaults.get(orchestrators.indexOf(o)).getName()))
                .toList();
        threads.forEach(Thread::start);

        if (!daemon) {
            // One-shot CLI mode: wait until all per-vault queues are empty,
            // then shut down cleanly.
            awaitIdle(queues, logService);
            scheduler.stop();
            broadcaster.interrupt();
            orchestrators.forEach(SyncOrchestrator::stop);
            logService.info("All queues drained - exiting.");
            logService.close();
            System.exit(0);
        }

        // Daemon mode (Tray): block until all orchestrator threads terminate
        // naturally via shutdown hook or external interrupt.
        threads.forEach(t -> {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Result of parsing raw CLI arguments into a command and its flags.
     *
     * <p>On failure, the error has already been printed to {@code stderr}
     * ({@link LogService} does not exist yet at this point in startup) —
     * the caller only needs to exit with {@link #errorExitCode()}.</p>
     */
    private record ParsedArgs(String command, Map<String, String> flags, Integer errorExitCode) {
        static ParsedArgs failure(int exitCode) {
            return new ParsedArgs(null, null, exitCode);
        }
        static ParsedArgs success(String command, Map<String, String> flags) {
            return new ParsedArgs(command, flags, null);
        }
        boolean isFailure() {
            return errorExitCode != null;
        }
    }

    /**
     * Parses raw CLI arguments into a positional command and a flag map.
     *
     * <p>For {@code vault}, a positional subcommand (e.g. {@code vault add}) is
     * extracted into the internal {@code "sub"} key before remaining arguments
     * are parsed as {@code --key=value} flags.</p>
     *
     * <p>Detects and reports (to {@code stderr}, non-fatal) any flag specified
     * more than once — except {@link #DUPLICATE_CHECK_EXEMPT_FLAGS}, where
     * repetition is harmless. Detects and rejects (fatal) any argument that does
     * not start with {@code --}, most commonly caused by a missing {@code =}
     * between a flag and its value (e.g. {@code --path /some/dir} instead of
     * {@code --path=/some/dir}).</p>
     *
     * <p>Contains no side effects beyond {@code stderr} output — safe to call
     * from a test without terminating the JVM, unlike the {@code System.exit}
     * calls this logic previously made inline.</p>
     *
     * @param args raw {@code String[]} from {@code main}
     * @return the parsed result, or a failure carrying the exit code to use
     */
    private static ParsedArgs parseArgs(String[] args) {
        if (args.length < 1) {
            System.err.println(
                    "Usage: java -jar NomadSync.jar <pull|push|sync|commit|autosave|status|config|vault> " +
                            "[subcommand] [--workspacePath=<path>] [--vault=<name|owner/name>] [--git.*=<value>]");
            return ParsedArgs.failure(1);
        }
        String command = args[0];

        // For commands that accept a positional subcommand (currently: vault),
        // args[1] — if present and not a flag — is extracted as "sub" before
        // the remaining flags are parsed. This keeps the public CLI surface clean:
        // users write `vault add` rather than `vault --sub=add`.
        Map<String, String> flags = new LinkedHashMap<>();
        int flagOffset = 1;
        if ("vault".equals(command) && args.length > 1 && !args[1].startsWith("--")) {
            flags.put("sub", args[1]);
            flagOffset = 2;
        }
        final int startFrom = flagOffset;

        Set<String> seenKeys = new HashSet<>();
        Set<String> duplicateKeys = new LinkedHashSet<>();

        Arrays.stream(args).skip(startFrom)
                .filter(a -> a.startsWith("--"))
                .forEach(arg -> {
                    String[] parts = arg.substring(2).split("=", 2);
                    String key = parts[0];
                    if (!DUPLICATE_CHECK_EXEMPT_FLAGS.contains(key) && !seenKeys.add(key)) {
                        duplicateKeys.add(key);
                    }
                    flags.put(key, parts.length > 1 ? parts[1] : "");
                });

        duplicateKeys.forEach(key ->
                System.err.println("Warning: --" + key + " was specified more than once - "
                        + "using the last value provided."));

        List<String> strayArgs = Arrays.stream(args).skip(startFrom)
                .filter(a -> !a.startsWith("--"))
                .toList();
        if (!strayArgs.isEmpty()) {
            System.err.println("Unrecognized argument(s): " + String.join(", ", strayArgs)
                    + " - did you forget '=' after a flag? (e.g. --path=<value>, not --path <value>)");
            return ParsedArgs.failure(1);
        }

        return ParsedArgs.success(command, flags);
    }

    /**
     * Flags for which repeated occurrence is harmless and should never trigger
     * the "specified more than once" warning — both are boolean/pure flags
     * (presence-only, no value that could be silently overwritten by a
     * duplicate) where repeating them changes nothing about the outcome.
     */
    private static final Set<String> DUPLICATE_CHECK_EXEMPT_FLAGS = Set.of(FLAG_FORCE);

    // ── Default workspace path resolution ─────────────────────────────────────

    private static final String WORKSPACES_REGISTRY_FILE_NAME = "workspaces.json";

    /**
     * Minimal shape read from {@code workspaces.json} for this single purpose —
     * only {@code defaultWorkspace.path} is ever consulted here. Not the real
     * registry DTO (that belongs to the full CRUD work — {@code workspace create}/
     * {@code add}/{@code rename}/etc.) — deliberately narrow, so this stopgap read
     * can be superseded later without having anticipated more of that surface than
     * this one call site actually needs today.
     */
    public record DefaultWorkspaceEntry(String workspaceName, String path) {}
    public record WorkspacesRegistrySnapshot(DefaultWorkspaceEntry defaultWorkspace) {}


    private static Path resolveWorkspacePathOrExit(String workspacePathArg) {
        try {
            return (workspacePathArg == null || workspacePathArg.isBlank())
                    ? JsonMapper.loadDefaultWorkspacePath(
                    resolveJarDirectory().resolve(WORKSPACES_REGISTRY_FILE_NAME).toFile())
                    : Path.of(workspacePathArg).toAbsolutePath().normalize();
        } catch (IOException e) {
            System.err.println("Unable to resolve default workspace path: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
            throw new AssertionError("unreachable — System.exit() above always terminates the JVM");
        }
    }

    /**
     * Directory containing the running JAR — used to locate {@code workspaces.json}
     * when resolving the default workspace path (no {@code --workspacePath} given),
     * so NomadSync finds its own registry regardless of the shell's current
     * working directory at invocation time (e.g. launched via a PATH entry from
     * an arbitrary location).
     *
     * @return the JAR's directory, or {@code "."} (the process's working
     *         directory) if it cannot be determined — defensive fallback, should
     *         not normally occur
     */
    private static Path resolveJarDirectory() {
        try {
            File jarFile = new File(Main.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return (jarFile.isFile() ? jarFile.getParentFile() : jarFile).toPath();
        } catch (URISyntaxException | NullPointerException e) {
            return Path.of(".");
        }
    }

    // ── Clean exit strategy ───────────────────────────────────────────────────

    /**
     * Flushes all log writers and terminates the process with the given exit code.
     *
     * <p>Must be used instead of {@link System#exit} for all early-exit paths
     * that occur after {@link LogService} is initialised. This ensures asynchronous
     * writers (e.g. {@link io.aledep10.nomadsync.logging.SeqHttpLogWriter}) have
     * time to drain their queues before the JVM kills daemon threads.</p>
     *
     * <p>The two {@link System#exit} calls that precede {@link LogService}
     * initialisation (argument validation and properties loading) remain as-is —
     * there is nothing to flush at that point.</p>
     *
     * @param logService the shared logging service to close before exit
     * @param exitCode   {@code 0} for success, {@code 1} for failure
     */
    static void exit(LogService logService, int exitCode) {
        logService.close();
        System.exit(exitCode);
    }

    // ── Queue management ──────────────────────────────────────────────────────

    /**
     * Blocks until all per-vault queues are empty, polling every 250ms.
     *
     * <p>Used in one-shot CLI mode to detect when the published event has been
     * consumed and processed by its orchestrator, so the process can terminate
     * without leaving work undone.</p>
     *
     * <p>A 500ms settling delay is applied after the queues first appear empty —
     * the orchestrator may briefly empty the queue before finishing its work
     * (e.g. the {@code git push} after a pull). The settling window reduces the
     * risk of exiting while an operation is still in flight.</p>
     *
     * @param queues     per-vault event queues
     * @param logService shared logging service
     */
    @SuppressWarnings("BusyWait") // one-shot CLI — process exits after idle; not a long-running loop
    private static void awaitIdle(List<SyncEventQueue> queues, LogService logService) {
        try {
            while (true) {
                boolean allEmpty = queues.stream().allMatch(SyncEventQueue::isEmpty);
                if (allEmpty) {
                    // Settling delay — confirm queues are still empty after 500ms
                    Thread.sleep(500);
                    allEmpty = queues.stream().allMatch(SyncEventQueue::isEmpty);
                    if (allEmpty) return;
                }
                Thread.sleep(250);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logService.info("awaitIdle interrupted");
        }
    }

    // ── Vault resolution ──────────────────────────────────────────────────────

    /**
     * Resolves the {@code --vault} flag to a {@link Vault} instance.
     *
     * <ul>
     *   <li>{@code null} flag → returns {@code null} (broadcast or mandatory error handled by caller)</li>
     *   <li>{@code owner/name} → exact {@link Vault#getRepoSlug()} match, otherwise {@link VaultNotFoundException}</li>
     *   <li>{@code name} → resolves if exactly one vault has that name;
     *       {@link VaultNotFoundException} if zero match, {@link VaultAmbiguousException} if multiple</li>
     * </ul>
     *
     * <p>Unlike the {@code null}-flag case, a non-null unresolvable flag is always
     * a fatal error for the caller — a vault name typed by the user and not found
     * must never be silently downgraded to a broadcast on all vaults.</p>
     *
     * @param vaultFlag  the raw {@code --vault} value, or {@code null} if absent
     * @param vaults     the list of registered vaults
     * @return the matching {@link Vault}, or {@code null} if {@code vaultFlag} is {@code null}
     * @throws VaultNotFoundException  if {@code vaultFlag} is non-null and matches no vault
     * @throws VaultAmbiguousException if {@code vaultFlag} is a bare name matching multiple vaults
     */
    private static Vault resolveVaultFlag(String vaultFlag, List<Vault> vaults)
            throws VaultNotFoundException, VaultAmbiguousException {
        if (vaultFlag == null) return null;

        if (vaultFlag.contains("/")) {
            return vaults.stream()
                    .filter(v -> v.getRepoSlug().equals(vaultFlag))
                    .findFirst()
                    .orElseThrow(() -> new VaultNotFoundException(vaultFlag));
        }

        List<Vault> matches = vaults.stream()
                .filter(v -> v.getName().equals(vaultFlag))
                .toList();

        if (matches.isEmpty()) {
            throw new VaultNotFoundException(vaultFlag);
        }
        if (matches.size() > 1) {
            throw new VaultAmbiguousException(vaultFlag, matches);
        }
        return matches.getFirst();
    }

    // ── Vault loading ─────────────────────────────────────────────────────────

    /**
     * Loads vaults from {@code catalog.json}.
     *
     * <p>On {@link VaultParseException}: logs a warning and returns an empty list —
     * the application continues from a clean state without crashing.</p>
     * <p>On {@link VaultIntegrityException} or any unexpected {@link VaultException}:
     * logs the error and terminates — manual intervention is required before
     * restarting.</p>
     *
     * @param vaultService the service responsible for vault persistence
     * @param logService   shared logging service
     * @return the list of loaded vaults, never {@code null}
     */
    private static List<Vault> loadVaults(VaultService vaultService, LogService logService) {
        try {
            return vaultService.load();
        } catch (VaultIntegrityException e) {
            logService.error("loadVaults - integrity violation: " + e.getMessage(), e);
            exit(logService, 1);
        } catch (VaultParseException e) {
            logService.warn("loadVaults - unreadable, starting with empty vault state: "
                    + e.getMessage());
            return new ArrayList<>();
        } catch (VaultException e) {
            logService.error("loadVaults - unexpected vault error: " + e.getMessage(), e);
            exit(logService, 1);
        }
        return new ArrayList<>(); // unreachable — satisfies compiler definite assignment
    }

    // ── Editor interaction ────────────────────────────────────────────────────

    /**
     * Opens the system text editor for the user to write a commit message.
     *
     * <p>Editor resolution order:
     * {@code --editor} flag → {@code commit.editor} property →
     * {@code EDITOR} env var → OS default ({@code notepad} on Windows,
     * {@code nano} elsewhere).</p>
     *
     * <p>Returns {@code null} if the temp file cannot be created or the editor
     * process fails.</p>
     *
     * @param flags      parsed CLI flags
     * @param properties loaded application properties
     * @param logService shared logging service
     * @return the trimmed commit message entered by the user, or {@code null} on failure
     */
    private static String openEditorForMessage(Map<String, String> flags,
                                               Properties properties,
                                               LogService logService) {
        try {
            Path tempFile = Files.createTempFile("nomadsync-commit-", ".txt");

            String editor = StringUtil.coalesce(
                    flags.get("editor"),
                    properties.getProperty(NomadProperties.Commit.EDITOR),
                    System.getenv("EDITOR"),
                    OsUtil.isWindows() ? "notepad" : "nano");

            new ProcessBuilder(editor, tempFile.toString())
                    .inheritIO()
                    .start()
                    .waitFor();

            String message = Files.readString(tempFile).strip();
            Files.deleteIfExists(tempFile);
            return message;

        } catch (IOException | InterruptedException e) {
            logService.error("openEditorForMessage - failed to open editor: " + e.getMessage());
            return null;
        }
    }

    // ── status command ────────────────────────────────────────────────────────

    /**
     * Handles the {@code status} command — prints {@code git status} output for
     * one or all registered vaults.
     *
     * <p>If {@code vaultFlag} is {@code null}, broadcasts to every vault in
     * {@code vaults}. If {@code vaultFlag} is present but cannot be resolved to
     * exactly one vault, the command fails immediately with no further action —
     * consistent with the rule that a non-null {@code --vault} value which does
     * not resolve is always a fatal error, regardless of the command.</p>
     *
     * @param vaultFlag  the raw {@code --vault} value, or {@code null} for broadcast
     * @param vaults     the list of registered vaults
     * @param gitService Git operations service
     * @param logService shared logging service
     * @return {@code 0} if the status was printed successfully for every target
     *         vault; {@code 1} if {@code --vault} could not be resolved, or if
     *         {@code git status} failed for at least one target vault
     */
    private static int handleStatus(String vaultFlag, List<Vault> vaults,
                                    GitService gitService, LogService logService) {
        List<Vault> targets;

        try {
            targets = vaultFlag != null
                    ? List.of(resolveVaultFlag(vaultFlag, vaults))
                    : vaults;
        } catch (VaultNotFoundException | VaultAmbiguousException e) {
            logService.error("handleStatus - " + e.getMessage());
            listRegistered(vaults, logService);
            return 1;
        }

        boolean multiVault = targets.size() > 1;
        boolean hadFailure = false;

        for (Vault vault : targets) {
            if (multiVault) logService.info("=== " + vault.getRepoSlug() + " ===");
            try {
                logService.info(gitService.status(vault));
            } catch (GitException e) {
                logService.error("handleStatus - failed for " + vault.getRepoSlug()
                        + " - " + e.getMessage());
                hadFailure = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logService.error("handleStatus - interrupted while reading status for "
                        + vault.getRepoSlug() + " - " + e.getMessage());
                hadFailure = true;
            }
            if (multiVault) logService.info("");
        }

        return hadFailure ? 1 : 0;
    }

    // ── config command ────────────────────────────────────────────────────────

    /**
     * Handles the {@code config} command.
     *
     * <p>With {@code --vault}: updates the matching vault's per-vault fields in
     * {@code catalog.json} and re-runs {@link GitService#bootstrapVault(Vault)}
     * to apply the changes immediately.</p>
     *
     * <p>Without {@code --vault}: updates matching {@code git.*} keys in
     * {@code config.properties} and persists to disk. Note: {@link Properties#store}
     * does not preserve comments from the original file.</p>
     *
     * @param flags        parsed CLI flags
     * @param vaultFlag    the raw {@code --vault} value, or {@code null} for global update
     * @param vaults       the list of registered vaults
     * @param vaultService vault persistence service
     * @param gitService   Git operations service
     * @param properties   loaded application properties
     * @param configFile   resolved path to the target workspace's {@code config.properties}
     * @param logService   shared logging service
     * @return {@code 0} on success, {@code 1} on error (vault not resolved, or
     *         persistence/bootstrap/write failure), {@code 2} if no {@code --git.*}
     *         flag was provided (no-op)
     */
    private static int handleConfig(Map<String, String> flags, String vaultFlag,
                                    List<Vault> vaults, VaultService vaultService,
                                    GitService gitService, Properties properties,
                                    Path configFile, LogService logService) {

        Map<String, String> gitFlags = new LinkedHashMap<>();
        flags.forEach((k, v) -> {
            if (k.startsWith("git.")) gitFlags.put(k, v);
        });

        if (gitFlags.isEmpty()) {
            logService.warn("handleConfig - no --git.* flags provided - nothing to update.");
            return 2;
        }

        if (vaultFlag != null) {
            Vault vault;
            try {
                vault = resolveVaultFlag(vaultFlag, vaults);
            } catch (VaultNotFoundException | VaultAmbiguousException e) {
                logService.error("handleConfig - " + e.getMessage());
                listRegistered(vaults, logService);
                return 1;
            }

            applyGitFlagsToVault(gitFlags, vault, logService);

            try {
                vaultService.update(vault);
                logService.info("handleConfig - vault " + vault.getRepoSlug()
                        + " updated in catalog.json");
                gitService.bootstrapVault(vault);
                logService.info("handleConfig - bootstrapVault re-applied for "
                        + vault.getRepoSlug());
            } catch (VaultException e) {
                logService.error("handleConfig - failed to persist vault update: "
                        + e.getMessage(), e);
                return 1;
            } catch (GitException e) {
                logService.error("handleConfig - bootstrapVault failed: " + e.getMessage(), e);
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logService.error("handleConfig - bootstrapVault interrupted: " + e.getMessage(), e);
                return 1;
            }

        } else {
            gitFlags.forEach((k, v) -> {
                properties.setProperty(k, v);
                String logged = NomadProperties.Git.TOKEN.equals(k) ? "<hidden>" : v;
                logService.info("handleConfig - set " + k + "=" + logged);
            });
            try (FileOutputStream out = new FileOutputStream(configFile.toFile())) {
                properties.store(out, "NomadSync configuration - updated by 'NomadSync config'");
                logService.info("handleConfig - config.properties updated at " + configFile);
            } catch (IOException e) {
                logService.error("handleConfig - failed to write config.properties: "
                        + e.getMessage(), e);
                return 1;
            }
        }
        return 0;
    }

    /**
     * Applies {@code --git.*} flags to the mutable credential and configuration
     * fields of the given {@link Vault}.
     *
     * <p>Unknown keys are logged as warnings and silently ignored — they do not
     * cause the command to fail.</p>
     *
     * @param gitFlags   map of {@code git.*} flag keys to their values
     * @param vault      the vault to mutate
     * @param logService shared logging service
     */
    private static void applyGitFlagsToVault(Map<String, String> gitFlags,
                                             Vault vault, LogService logService) {
        gitFlags.forEach((key, value) -> {
            switch (key) {
                case NomadProperties.Git.NAME     -> vault.setGitName(value);
                case NomadProperties.Git.EMAIL    -> vault.setGitEmail(value);
                case NomadProperties.Git.USERNAME -> vault.setGitUsername(value);
                case NomadProperties.Git.TOKEN    -> vault.setGitToken(value);
                case NomadProperties.Git.BRANCH   -> vault.setGitBranch(value);
                case NomadProperties.Git.REMOTE   -> vault.setGitRemote(value);
                default -> logService.warn("applyGitFlagsToVault: unknown flag '"
                        + key + "' - ignored");
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Maps a CLI command string to the corresponding {@link EventType},
     * or {@code null} for commands that do not produce orchestrator events
     * ({@code autosave}, {@code status}, {@code config}, {@code vault}).
     *
     * @param command    the CLI command string
     * @param logService shared logging service
     * @return the corresponding {@link EventType}, or {@code null}
     */
    private static EventType operationToEventType(String command, LogService logService) {
        EventType eventType = switch (command) {
            case "pull"     -> EventType.PULL_LOGON;
            case "push"     -> EventType.PUSH_LOGOFF;
            case "sync"     -> EventType.SYNCHRONIZE;
            case "commit"   -> EventType.COMMIT_MANUAL;
            default         -> null;        // includes also "autosave", "status", "config", "vault"
        };
        logService.debug("operationToEventType: " + command + " → " + eventType);
        return eventType;
    }

    /**
     * Logs all registered vault repoSlugs at ERROR level — used in resolution
     * error messages to help the user identify the correct {@code --vault} value.
     *
     * @param vaults     the list of registered vaults
     * @param logService shared logging service
     */
    private static void listRegistered(List<Vault> vaults, LogService logService) {
        logService.error("Registered: "
                + vaults.stream()
                .map(Vault::getRepoSlug)
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)"));
    }

    // ── Vault CLI handlers ────────────────────────────────────────────────────

    // Allowed flags per vault subcommand.
    // "sub" is injected internally by the parser and is always implicitly allowed.
    // Global flags (config, vault, daemon) are removed from the map before these
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
    private static final Set<String> FLAGS_VAULT_REMOVE = Set.of("vault", FLAG_FORCE);
    private static final Set<String> FLAGS_VAULT_RELOCATE =
            Set.of("vault", "owner", "name", "path",
                    "git.name", "git.email", "git.username",
                    "git.token", "git.branch", "git.remote", FLAG_FORCE);
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
     * Computes the Levenshtein edit distance between two strings — the minimum
     * number of single-character insertions, deletions, or substitutions needed
     * to transform one into the other. Case-insensitive, since flag names are
     * conventionally lowercase and a typo shouldn't hide behind a case mismatch.
     *
     * @param a first string
     * @param b second string
     * @return the edit distance, always {@code >= 0}
     */
    private static int levenshteinDistance(String a, String b) {
        String s1 = a.toLowerCase();
        String s2 = b.toLowerCase();
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(
                                dp[i - 1][j] + 1,      // deletion
                                dp[i][j - 1] + 1),     // insertion
                        dp[i - 1][j - 1] + cost); // substitution
            }
        }
        return dp[s1.length()][s2.length()];
    }

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
                .map(known -> Map.entry(known, levenshteinDistance(unknownFlag, known)))
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
    private static int handleVaultCreate(Map<String, String> flags, List<Vault> vaults,
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
    private static int handleVaultAdd(Map<String, String> flags, List<Vault> vaults,
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
    private static int handleVaultUpdate(Map<String, String> flags, List<Vault> vaults,
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
    private static int handleVaultRemove(Map<String, String> flags, List<Vault> vaults,
                                         VaultService vaultService,
                                         LogService logService) {
        if (hasUnknownFlags(flags, FLAGS_VAULT_REMOVE, "handleVaultRemove", logService)) return 1;
        if (hasBlankRequiredFlags(flags, Set.of("vault"), "handleVaultRemote", logService)) return 1;
        if (hasBlankOptionalValue(flags, Set.of("owner", "name", "path"), "handleVaultRemove", logService)) return 1;

        Vault vault;
        try {
            vault = resolveVaultFlag(flags.get("vault"), vaults);
        } catch (VaultNotFoundException | VaultAmbiguousException e) {
            logService.error("handleVaultRemove - " + e.getMessage());
            listRegistered(vaults, logService);
            return 1;
        }

        if (!flags.containsKey(FLAG_FORCE)) {
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
    private static int handleVaultRelocate(Map<String, String> flags, List<Vault> vaults,
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

        if (!flags.containsKey(FLAG_FORCE)) {
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
            try {
                FileUtil.copyRecursively(Path.of(vault.getPath()), Path.of(newPath));
            } catch (IOException e) {
                logService.error("handleVaultRelocate - copy failed: " + e.getMessage(), e);
                return 1;
            }
            // The raw copy may have carried over the OLD .vault marker (if one existed at
            // the original location) — it must not occupy the new location's claim slot,
            // since vaultService.update() below will atomically claim a fresh marker there
            // via claimVaultPath. Remove it defensively; harmless no-op if none was copied.
            try {
                Files.deleteIfExists(Path.of(newPath).resolve(".vault"));
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
    private static int handleVaultList(Map<String, String> flags, List<Vault> vaults, LogService logService) {
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
    private static int handleVaultShow(Map<String, String> flags, List<Vault> vaults,
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
    private static String orDefault(String value) {
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
    private static boolean hasBlankRequiredFlags(Map<String, String> flags, Set<String> requiredKeys,
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
     * <p>{@code --config} is always implicitly checked in addition to
     * {@code structuralKeys}, regardless of what the caller passes — it is a
     * global flag present on every command, and a blank value
     * ({@code --config=}) is never valid on any of them. Callers do not need to
     * (and should not) include {@code "config"} in {@code structuralKeys}
     * themselves.</p>
     *
     * @param flags         parsed CLI flags
     * @param structuralKeys optional structural keys to check when present
     *                       (e.g. {@code owner}, {@code name}, {@code path});
     *                       may be empty if the handler has none of its own —
     *                       {@code --config} is still checked in that case
     * @param handler       handler name used as log prefix, e.g. {@code "handleVaultUpdate"}
     * @param logService    shared logging service
     * @return {@code true} if at least one checked key is present but blank,
     *         {@code false} otherwise
     */
    private static boolean hasBlankOptionalValue(Map<String, String> flags, Set<String> structuralKeys,
                                                 String handler, LogService logService) {
        Set<String> keysToCheck = new java.util.HashSet<>(structuralKeys);
        keysToCheck.add("config");

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
    private static final Map<String, String> FLAG_SYNTAX_HINTS = Map.of(
            "vault", "--vault=<name|owner/name>"
    );
}