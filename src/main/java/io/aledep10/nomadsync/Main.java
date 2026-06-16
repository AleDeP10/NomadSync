package io.aledep10.nomadsync;

import io.aledep10.nomadsync.config.NomadProperties;
import io.aledep10.nomadsync.exception.GitException;
import io.aledep10.nomadsync.exception.VaultException;
import io.aledep10.nomadsync.hook.LogNotificationHook;
import io.aledep10.nomadsync.hook.NotificationHook;
import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.orchestrator.SyncEvent;
import io.aledep10.nomadsync.orchestrator.SyncEventQueue;
import io.aledep10.nomadsync.orchestrator.SyncOrchestrator;
import io.aledep10.nomadsync.orchestrator.Vault;
import io.aledep10.nomadsync.scheduler.AutosaveScheduler;
import io.aledep10.nomadsync.service.GitService;
import io.aledep10.nomadsync.service.GitignoreService;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.service.VaultService;
import io.aledep10.nomadsync.util.OsUtil;
import io.aledep10.nomadsync.util.StringUtil;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Entry point for NomadSync.
 *
 * <h2>CLI syntax</h2>
 * <pre>
 *   java -jar NomadSync.jar &lt;operation&gt; [--config=&lt;path&gt;] [--vault=&lt;name|owner/name&gt;] [--daemon] [flags...]
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
 * <p>{@code operation} is positional and always required. All other arguments are
 * flags in {@code --key=value} form, order-independent.</p>
 *
 * <h2>Operations</h2>
 * <ul>
 *   <li>{@code pull}    — stash → pull → stash pop. Broadcasts to all vaults if {@code --vault} is absent.</li>
 *   <li>{@code push}    — commit local → push. Broadcasts if {@code --vault} is absent.</li>
 *   <li>{@code sync}    — full bidirectional sync. Broadcasts if {@code --vault} is absent.</li>
 *   <li>{@code commit}  — opens editor, commits locally with user message.
 *       {@code --vault} is mandatory ({@link EventType#COMMIT_MANUAL}).</li>
 *   <li>{@code autosave} — no-op; the {@link AutosaveScheduler} handles periodic publishing.</li>
 *   <li>{@code config}  — updates {@code config.properties} (global) or {@code vaults.json}
 *       (per-vault). Does not start orchestrators.</li>
 * </ul>
 *
 * <h2>Vault resolution ({@code --vault})</h2>
 * <ul>
 *   <li>{@code --vault=name} — unambiguous if exactly one vault has that name;
 *       error if zero or more than one match.</li>
 *   <li>{@code --vault=owner/name} — exact repoSlug match.</li>
 *   <li>absent — broadcast (for non-mandatory operations) or error (for mandatory).</li>
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
 * <ol>
 *   <li>Parse CLI flags and load configuration.</li>
 *   <li>Bootstrap shared dependencies.</li>
 *   <li>Load registered vaults and resolve {@code --vault} if present.</li>
 *   <li>Bootstrap per-vault Git credentials via {@link GitService#bootstrapVault(Vault)}.</li>
 *   <li>Wire one {@link SyncOrchestrator} + {@link SyncEventQueue} per vault.</li>
 *   <li>Wire a broadcast queue and dispatcher thread.</li>
 *   <li>Translate the operation into a typed {@link SyncEvent} and publish it.</li>
 *   <li>Start the autosave scheduler, broadcaster, and all orchestrators.</li>
 * </ol>
 */
public class Main {

    public static void main(String[] args) throws IOException {

        // ── 1. Parse operation and flags ──────────────────────────────────────
        if (args.length < 1) {
            System.err.println(
                    "Usage: java -jar NomadSync.jar <pull|push|sync|commit|autosave|config> " +
                            "[--config=<path>] [--vault=<name|owner/name>] [--git.*=<value>]");
            System.exit(1);
        }
        String operation = args[0];

        Map<String, String> flags = new LinkedHashMap<>();
        Arrays.stream(args).skip(1)
                .filter(a -> a.startsWith("--"))
                .forEach(arg -> {
                    String[] parts = arg.substring(2).split("=", 2);
                    flags.put(parts[0], parts.length > 1 ? parts[1] : "");
                });

        String configPath = flags.getOrDefault("config", "./config.properties");
        String vaultFlag  = flags.get("vault");
        boolean daemon    = flags.containsKey("daemon");

        // ── 2. Load configuration ─────────────────────────────────────────────
        Properties properties = new Properties();
        try {
            properties.load(new FileInputStream(configPath));
        } catch (IOException e) {
            System.err.println("Unable to load properties file: " + configPath);
            System.exit(1);
        }

        // ── 3. Bootstrap shared dependencies ──────────────────────────────────
        LogService       logService       = new LogService(properties);
        GitignoreService gitignoreService = new GitignoreService(logService);
        VaultService     vaultService     = new VaultService(properties, gitignoreService, logService);
        GitService       gitService       = new GitService(properties, vaultService, logService);
        NotificationHook hook             = new LogNotificationHook(logService);

        // ── 4. Load vaults ────────────────────────────────────────────────────
        final List<Vault> vaults;
        try {
            vaults = vaultService.load();
        } catch (IOException | VaultException e) {
            logService.error("Unable to load vaults.json: " + e.getMessage(), e);
            System.exit(1);
            return;
        }

        if (vaults.isEmpty()) {
            logService.warn("No vaults registered — nothing to do.");
            System.exit(0);
        }

        // ── 5. Early-exit operations — no orchestrators needed ───────────────────
        if ("status".equals(operation)) {
            handleStatus(vaultFlag, vaults, gitService, logService);
            System.exit(0);
        }

        if ("config".equals(operation)) {
            handleConfig(flags, vaultFlag, vaults, vaultService, gitService, properties,
                    configPath, logService);
            System.exit(0);
        }

        // ── 6. Resolve --vault flag (after vaults are loaded) ─────────────────
        Vault targetVault = resolveVaultFlag(vaultFlag, vaults, logService);

        // Validate mandatory-vault constraint
        EventType eventType = operationToEventType(operation, logService);
        if (eventType != null && eventType.isMandatoryVault() && targetVault == null) {
            logService.error("operation '" + operation + "' requires --vault=<name|owner/name>");
            listRegistered(vaults, logService);
            System.exit(1);
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
            LogService       vaultLog = logService.withVault(vault.getRepoSlug());
            SyncEventQueue   queue    = new SyncEventQueue(vaultLog);
            SyncOrchestrator orch     = new SyncOrchestrator(vault, gitService, vaultLog, queue, hook);
            queues.add(queue);
            orchestrators.add(orch);
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
                                    + event.getVaultId() + " — event discarded");
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
        long intervalMinutes = Long.parseLong(
                properties.getProperty(NomadProperties.Autosave.INTERVAL_MINUTES, "15"));
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
        switch (operation) {
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
                    logService.info("Empty commit message — aborting, no commit created.");
                    System.exit(0);
                }
                broadcastQueue.publish(
                        new SyncEvent(EventType.COMMIT_MANUAL, targetVault.getId(), message));
            }
            case "autosave" -> { /* AutosaveScheduler handles periodic publishing */ }
            default -> {
                logService.error("Unknown operation: " + operation);
                System.exit(1);
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
            // then shut down cleanly. The scheduler is stopped first to avoid
            // new AUTOSAVE events being published while we drain.
            awaitIdle(queues, logService);
            scheduler.stop();
            broadcaster.interrupt();
            orchestrators.forEach(SyncOrchestrator::stop);
            logService.info("All queues drained — exiting.");
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
     * Resolves {@code --vault} flag to a {@link Vault} instance.
     *
     * <ul>
     *   <li>{@code null} flag → returns {@code null} (broadcast or mandatory error handled by caller)</li>
     *   <li>{@code owner/name} → exact repoSlug match</li>
     *   <li>{@code name} → unambiguous if exactly one match; error if zero or multiple</li>
     * </ul>
     */
    private static Vault resolveVaultFlag(String vaultFlag, List<Vault> vaults,
                                          LogService logService) {
        if (vaultFlag == null) return null;

        if (vaultFlag.contains("/")) {
            // exact repoSlug
            return vaults.stream()
                    .filter(v -> v.getRepoSlug().equals(vaultFlag))
                    .findFirst()
                    .orElseGet(() -> {
                        logService.error("vault '" + vaultFlag + "' not found.");
                        listRegistered(vaults, logService);
                        System.exit(1);
                        return null;
                    });
        }

        // name-only: collect all matches
        List<Vault> matches = vaults.stream()
                .filter(v -> v.getName().equals(vaultFlag))
                .toList();

        if (matches.isEmpty()) {
            logService.error("vault '" + vaultFlag + "' not found.");
            listRegistered(vaults, logService);
            System.exit(1);
        }
        if (matches.size() > 1) {
            logService.error("vault name '" + vaultFlag + "' is ambiguous. Matches:");
            matches.forEach(v -> logService.error("  " + v.getRepoSlug()));
            logService.error("Use --vault=<owner>/<name>");
            System.exit(1);
        }
        return matches.getFirst();
    }

    // ── Editor interaction ────────────────────────────────────────────────────

    /**
     * Opens the system text editor for the user to write a commit message.
     *
     * <p>Editor resolution order:
     * {@code --editor} flag → {@code commit.editor} property →
     * {@code EDITOR} env var → OS default ({@code notepad} / {@code nano}).</p>
     *
     * <p>Returns {@code null} if the temp file cannot be created or read.</p>
     *
     * @return the trimmed commit message, or {@code null} on I/O failure
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
            logService.error("Failed to open editor: " + e.getMessage());
            return null;
        }
    }

    // ── config operation ──────────────────────────────────────────────────────

    /**
     * Handles the {@code status} operation.
     *
     * <p>Prints the human-readable output of {@code git status} to {@code stdout}.
     * With {@code --vault}: shows status for the specified vault only.
     * Without {@code --vault}: shows status for all registered vaults, each
     * preceded by a header line ({@code === <repoSlug> ===}) for readability.</p>
     *
     * <p>Output goes to {@code System.out} — this is an interactive response to
     * the user, not a system event, so {@code logService} is not used.</p>
     */
    private static void handleStatus(String vaultFlag, List<Vault> vaults,
                                     GitService gitService, LogService logService) {
        List<Vault> targets = vaultFlag != null
                ? List.of(resolveVaultFlag(vaultFlag, vaults, logService))
                : vaults;

        boolean multiVault = targets.size() > 1;
        for (Vault vault : targets) {
            if (multiVault) {
                System.out.println("=== " + vault.getRepoSlug() + " ===");
            }
            try {
                System.out.println(gitService.status(vault));
            } catch (GitException | InterruptedException e) {
                logService.error("status failed for " + vault.getRepoSlug()
                        + ": " + e.getMessage());
            }
            if (multiVault) System.out.println();
        }
    }

    /**
     * Handles the {@code config} operation.
     *
     * <p>With {@code --vault}: updates the matching vault's per-vault fields in
     * {@code vaults.json} and re-runs {@link GitService#bootstrapVault(Vault)}
     * to apply immediately.</p>
     *
     * <p>Without {@code --vault}: updates matching {@code git.*} keys in
     * {@code config.properties} and persists. Note: {@link Properties#store}
     * does not preserve comments from the original file.</p>
     */
    private static void handleConfig(Map<String, String> flags, String vaultFlag,
                                     List<Vault> vaults, VaultService vaultService,
                                     GitService gitService, Properties properties,
                                     String configPath, LogService logService) {
        Map<String, String> gitFlags = new LinkedHashMap<>();
        flags.forEach((k, v) -> {
            if (k.startsWith("git.")) gitFlags.put(k, v);
        });

        if (gitFlags.isEmpty()) {
            logService.warn("config: no --git.* flags provided — nothing to update.");
            return;
        }

        if (vaultFlag != null) {
            // per-vault update
            Vault vault = resolveVaultFlag(vaultFlag, vaults, logService);
            if (vault == null) return;

            applyGitFlagsToVault(gitFlags, vault, logService);

            try {
                vaultService.update(vault);
                logService.info("vault " + vault.getRepoSlug() + " updated in vaults.json");
                gitService.bootstrapVault(vault);
                logService.info("bootstrapVault re-applied for " + vault.getRepoSlug());
            } catch (IOException | VaultException e) {
                logService.error("Failed to persist vault update: " + e.getMessage(), e);
            } catch (GitException | InterruptedException e) {
                logService.error("bootstrapVault failed after config update: "
                        + e.getMessage(), e);
            }

        } else {
            // global update — config.properties
            gitFlags.forEach((k, v) -> {
                properties.setProperty(k, v);
                String logged = NomadProperties.Git.TOKEN.equals(k) ? "<hidden>" : v;
                logService.info("config: set " + k + "=" + logged);
            });
            try (FileOutputStream out = new FileOutputStream(configPath)) {
                properties.store(out,
                        "NomadSync configuration — updated by 'NomadSync config'");
                logService.info("config.properties updated at " + configPath);
            } catch (IOException e) {
                logService.error("Failed to write config.properties: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Applies {@code --git.*} flags to the mutable fields of the given {@link Vault}.
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
                default -> logService.warn("config: unknown git flag '" + key + "' — ignored");
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Maps a CLI operation string to the corresponding {@link EventType},
     * or {@code null} for operations that don't produce events ({@code autosave},
     * {@code config}).
     */
    private static EventType operationToEventType(String operation, LogService logService) {
        return switch (operation) {
            case "pull"     -> EventType.PULL_LOGON;
            case "push"     -> EventType.PUSH_LOGOFF;
            case "sync"     -> EventType.SYNCHRONIZE;
            case "commit"   -> EventType.COMMIT_MANUAL;
            case "autosave" -> null;
            case "status"   -> null;
            default         -> null;
        };
    }

    /**
     * Logs all registered vault repoSlugs — used in error messages to help the
     * user correct a {@code --vault} flag.
     */
    private static void listRegistered(List<Vault> vaults, LogService logService) {
        logService.error("Registered: "
                + vaults.stream()
                .map(Vault::getRepoSlug)
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)"));
    }
}