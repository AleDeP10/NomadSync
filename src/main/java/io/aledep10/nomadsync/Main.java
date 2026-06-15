package io.aledep10.nomadsync;

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

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Entry point for NomadSync.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li>Parse CLI arguments and load configuration.</li>
 *   <li>Bootstrap shared dependencies.</li>
 *   <li>Load registered vaults from {@code vaults.json}.</li>
 *   <li>Wire one {@link SyncOrchestrator} + {@link SyncEventQueue} per vault.</li>
 *   <li>Wire a broadcast queue and a broadcaster thread that fans out events
 *       with {@code vaultId = null} to all per-vault queues, and routes events
 *       with a specific {@code vaultId} to the matching queue only.</li>
 *   <li>Translate the CLI argument into a typed {@link SyncEvent} and publish it.</li>
 *   <li>Start the autosave scheduler, the broadcaster, and all orchestrators.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -jar NomadSync.jar [pull|push|sync|autosave] config.properties [vaultId]
 * </pre>
 *
 * <p>{@code vaultId} is optional. For {@code pull}, {@code push}, and {@code sync}
 * it scopes the operation to a single vault — defaults to the first registered vault
 * if absent. For {@code autosave} it is ignored — the scheduler broadcasts to all
 * vaults automatically.</p>
 */
public class Main {

    public static void main(String[] args) {

        // ── 1. Validate args ──────────────────────────────────────────────────
        if (args.length < 2) {
            System.err.println(
                    "Usage: java -jar NomadSync.jar [pull|push|sync|autosave] <properties_file> [vaultId]");
            System.exit(1);
        }
        String operation     = args[0];
        String configPath    = args[1];
        String targetVaultId = args.length >= 3 ? args[2] : null;

        // ── 2. Load configuration ─────────────────────────────────────────────
        Properties properties = new Properties();
        try {
            properties.load(new FileInputStream(configPath));
        } catch (IOException e) {
            System.err.println("Unable to load properties file: " + configPath);
            System.exit(1);
        }
        properties.list(System.out);

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
            return; // unreachable — satisfies compiler
        }

        if (vaults.isEmpty()) {
            logService.warn("No vaults registered — nothing to do.");
            System.exit(0);
        }

        // ── 5. Wire one queue + orchestrator per vault ─────────────────────────
        List<SyncEventQueue>   queues        = new ArrayList<>();
        List<SyncOrchestrator> orchestrators = new ArrayList<>();

        for (Vault vault : vaults) {
            Properties vaultProperties = new Properties(properties);
            vaultProperties.setProperty("vault.path", vault.getPath());

            LogService     vaultLog = logService.withVault(vault.getRepoSlug());
            SyncEventQueue queue    = new SyncEventQueue(vaultLog);
            SyncOrchestrator orch   = new SyncOrchestrator(
                    vaultProperties, gitService, vaultLog, queue, hook);

            queues.add(queue);
            orchestrators.add(orch);
        }

        // ── 6. Broadcast queue + broadcaster thread ───────────────────────────
        //
        // The broadcaster consumes events from the broadcast queue and routes them:
        //   vaultId = null  → fan-out to ALL per-vault queues   (AUTOSAVE, SYNCHRONIZE-all)
        //   vaultId != null → publish to the matching queue only (targeted SYNCHRONIZE)
        //
        // This keeps AutosaveScheduler and future tray actions decoupled from the
        // per-vault queue list — they publish to a single well-known channel.
        SyncEventQueue broadcastQueue = new SyncEventQueue(logService);

        Thread broadcaster = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    SyncEvent event = broadcastQueue.consume();
                    if (event.getVaultId() == null) {
                        // broadcast — fan-out to all vaults
                        queues.forEach(q -> q.publish(event));
                    } else {
                        // targeted — route to the matching vault queue
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
                            logService.warn("Broadcaster: unknown vaultId " + event.getVaultId()
                                    + " — event discarded");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            logService.info("Broadcaster: stopped.");
        }, "nomadsync-broadcaster");

        // ── 7. AutosaveScheduler ──────────────────────────────────────────────
        long intervalMinutes = Long.parseLong(
                properties.getProperty("autosave.interval.minutes", "15"));
        AutosaveScheduler scheduler = new AutosaveScheduler(
                broadcastQueue, logService, intervalMinutes);

        // ── 8. Shutdown hook ──────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.stop();
            broadcaster.interrupt();
            orchestrators.forEach(SyncOrchestrator::stop);
            logService.close();
        }, "nomadsync-shutdown"));

        // ── 9. CLI → event ────────────────────────────────────────────────────
        // Targeted operations default to the first vault when vaultId is absent.
        // AUTOSAVE is handled entirely by the scheduler — no event published here.
        switch (operation) {
            case "pull" -> {
                String id = resolveTargetId(vaults, targetVaultId, logService);
                routeToVault(queues, vaults, new SyncEvent(EventType.PULL_LOGON, id), logService);
            }
            case "push" -> {
                String id = resolveTargetId(vaults, targetVaultId, logService);
                routeToVault(queues, vaults, new SyncEvent(EventType.PUSH_LOGOFF, id), logService);
            }
            case "sync" -> {
                if (targetVaultId == null) {
                    // broadcast SYNCHRONIZE to all vaults
                    broadcastQueue.publish(new SyncEvent(EventType.SYNCHRONIZE, null));
                } else {
                    String id = resolveTargetId(vaults, targetVaultId, logService);
                    routeToVault(queues, vaults,
                            new SyncEvent(EventType.SYNCHRONIZE, id), logService);
                }
            }
            case "autosave" -> { /* AutosaveScheduler handles periodic publishing */ }
            default -> {
                logService.error("Unknown operation: " + operation);
                System.exit(1);
            }
        }

        // ── 10. Start ─────────────────────────────────────────────────────────
        // Each SyncOrchestrator.start() blocks on worker.join() — run on separate
        // threads and join all to keep the main thread alive until all vaults stop.
        scheduler.start();
        broadcaster.start();

        List<Thread> threads = orchestrators.stream()
                .map(o -> new Thread(o::start,
                        "nomadsync-main-" + vaults.get(orchestrators.indexOf(o)).getName()))
                .toList();
        threads.forEach(Thread::start);
        threads.forEach(t -> {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the target vault ID from the CLI argument.
     *
     * <p>If {@code targetVaultId} is provided but not found in the vault list,
     * a warning is logged and the first vault is used as fallback.</p>
     *
     * @param vaults        registered vaults
     * @param targetVaultId vault id from CLI, or {@code null}
     * @param logService    shared logging service
     * @return the resolved vault id — never {@code null}
     */
    private static String resolveTargetId(List<Vault> vaults, String targetVaultId,
                                          LogService logService) {
        if (targetVaultId == null) return vaults.getFirst().getId();
        boolean found = vaults.stream().anyMatch(v -> v.getId().equals(targetVaultId));
        if (!found) {
            logService.warn("vaultId '" + targetVaultId
                    + "' not found — falling back to first vault");
            return vaults.getFirst().getId();
        }
        return targetVaultId;
    }

    /**
     * Publishes an event directly to the per-vault queue matching the event's
     * {@code vaultId}.
     *
     * @param queues     per-vault queues in the same order as {@code vaults}
     * @param vaults     registered vaults
     * @param event      event to publish — must carry a non-null {@code vaultId}
     * @param logService shared logging service
     */
    private static void routeToVault(List<SyncEventQueue> queues, List<Vault> vaults,
                                     SyncEvent event, LogService logService) {
        for (int i = 0; i < vaults.size(); i++) {
            if (vaults.get(i).getId().equals(event.getVaultId())) {
                queues.get(i).publish(event);
                return;
            }
        }
        logService.error("routeToVault: vaultId " + event.getVaultId() + " not found");
    }
}