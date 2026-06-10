package io.aledep10.nomadsync;

import io.aledep10.nomadsync.hook.LogNotificationHook;
import io.aledep10.nomadsync.hook.NotificationHook;
import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.orchestrator.SyncEvent;
import io.aledep10.nomadsync.orchestrator.SyncEventQueue;
import io.aledep10.nomadsync.orchestrator.SyncOrchestrator;
import io.aledep10.nomadsync.scheduler.AutosaveScheduler;
import io.aledep10.nomadsync.service.GitService;
import io.aledep10.nomadsync.service.GitignoreService;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.service.VaultService;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Entry point for ObsidianSync.
 *
 * <p>Responsibilities:</p>
 * <ol>
 *   <li>Parse CLI arguments and load configuration</li>
 *   <li>Bootstrap all dependencies (manual dependency injection)</li>
 *   <li>Translate the CLI argument into a typed {@link SyncEvent} and publish it</li>
 *   <li>Start the orchestrator and block until processing completes</li>
 * </ol>
 *
 * <p>Usage:</p>
 * <pre>
 *   java -jar ObsidianSync.jar [pull|push|autosave] [config.properties]
 * </pre>
 */
public class Main {
    public static void main(String[] args) {
        if (args == null || args.length < 2) {
            System.err.println("Usage: java -jar ObsidianSync.jar [pull|push|autosave] <properties_file>");
            System.exit(1);
        }

        Properties properties = new Properties();
        try {
            properties.load(new FileInputStream(args[1]));
        } catch (IOException e) {
            System.err.println("Unable to load configuration file: " + args[1]);
            System.exit(1);
        }

        long interval = Long.parseLong(properties.getProperty("autosave.interval.minutes"));

        // ── Dependency wiring ────────────────────────────────────────────────
        LogService logService               = new LogService(properties);
        GitignoreService gitignoreService   = new GitignoreService(logService);
        VaultService vaultService           = new VaultService(properties, gitignoreService, logService);
        GitService gitService               = new GitService(properties, vaultService, logService);
        SyncEventQueue queue                = new SyncEventQueue(logService);
        NotificationHook hook               = new LogNotificationHook(logService);
        SyncOrchestrator orchestrator       = new SyncOrchestrator(properties, gitService, logService, queue, hook);
        AutosaveScheduler scheduler         = new AutosaveScheduler(queue, logService, interval);

        // ── Shutdown hook — registered before start, order matters ──────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.stop();     // stop publishing first
            orchestrator.stop();  // then drain and stop consuming
        }, "obsidiansync-shutdown"));

        // ── CLI argument → event ─────────────────────────────────────────────
        switch (args[0]) {
            // [TODO] questi case non sono più sufficienti: occorrerà introdurre i pull e push manuali
            // [TARGET] fornire i valori corretti da aspettersi per il primo parametro
            case "pull"     -> queue.publish(new SyncEvent(EventType.PULL_LOGON, null));
            case "push"     -> queue.publish(new SyncEvent(EventType.PUSH_LOGOFF, null));
            case "autosave" -> { /* AutosaveScheduler handles periodic publishing */ }
            case null, default -> {
                logService.error("Unknown operation: " + args[0]);
                logService.error("Usage: java -jar ObsidianSync.jar [pull|push|autosave] <properties_file>");
                System.exit(1);
            }
        }

        // ── Start — scheduler first, then orchestrator (blocks on worker.join) ──
        scheduler.start();
        // [TODO] l'avvio di orchestrator andrà spostato nella Tray
        orchestrator.start();
    }
}
