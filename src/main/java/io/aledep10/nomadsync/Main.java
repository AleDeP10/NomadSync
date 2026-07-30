package io.aledep10.nomadsync;

import io.aledep10.nomadsync.cli.VaultCli;
import io.aledep10.nomadsync.config.NomadProperties;
import io.aledep10.nomadsync.exception.*;
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

    public static final String CONFIG_FILE_NAME = "config.properties";
    public static final String FLAG_FORCE = "force";

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
        VaultCli         vaultCli         = new VaultCli(vaultService, markerService, gitService, logService);
        NotificationHook hook             = new LogNotificationHook(logService);

        // ── 4. Load vaults ────────────────────────────────────────────────────
        final List<Vault> vaults = loadVaults(vaultService, logService);

        // ── 5. Early-exit commands — no orchestrators needed ──────────────────
        if ("vault".equals(command)) {
            // vault subcommands are allowed on an empty registry (e.g. vault add)
            String subcommand = flags.getOrDefault("sub", "list");
            int result = vaultCli.execute(subcommand, flags, vaults);
            exit(logService, result);
        }

        // For all other commands, an empty vault registry is a no-op.
        if (vaults.isEmpty()) {
            logService.warn("No vaults registered - nothing to do.");
            exit(logService, 0);
        }

        if ("status".equals(command)) {
            exit(logService, handleStatus(vaultFlag, vaults, vaultService, gitService, logService));
        }

        if ("config".equals(command)) {
            exit(logService,
                    handleConfig(
                            flags, vaultFlag, vaults, vaultService, gitService, properties, configFile, logService));
        }

        // ── 6. Resolve --vault flag ───────────────────────────────────────────
        Vault targetVault;
        try {
            targetVault = vaultService.resolveVaultFlag(vaultFlag);
        } catch (VaultNotFoundException | VaultAmbiguousException e) {
            logService.error("Main - " + e.getMessage());
            vaultService.listRegistered();
            exit(logService, 1);
            return;
        }

        // Validate mandatory-vault constraint
        EventType eventType = operationToEventType(command, logService);
        if (eventType != null && eventType.isMandatoryVault() && targetVault == null) {
            logService.error("command '" + command + "' requires --vault=<name|owner/name>");
            vaultService.listRegistered();
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
     * @param vaultService vault operations service
     * @param gitService Git operations service
     * @param logService shared logging service
     * @return {@code 0} if the status was printed successfully for every target
     *         vault; {@code 1} if {@code --vault} could not be resolved, or if
     *         {@code git status} failed for at least one target vault
     */
    private static int handleStatus(String vaultFlag, List<Vault> vaults,
                                    VaultService vaultService, GitService gitService, LogService logService) {
        List<Vault> targets;

        try {
            targets = vaultFlag != null
                    ? List.of(vaultService.resolveVaultFlag(vaultFlag))
                    : vaults;
        } catch (VaultNotFoundException | VaultAmbiguousException e) {
            logService.error("handleStatus - " + e.getMessage());
            vaultService.listRegistered();
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
                vault = vaultService.resolveVaultFlag(vaultFlag);
            } catch (VaultNotFoundException | VaultAmbiguousException e) {
                logService.error("handleConfig - " + e.getMessage());
                vaultService.listRegistered();
                return 1;
            }

            vaultService.applyGitFlagsToVault(gitFlags, vault);

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
}