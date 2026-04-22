package io.aledep10.obsidiansync;

import io.aledep10.obsidiansync.hook.LogNotificationHook;
import io.aledep10.obsidiansync.hook.NotificationHook;
import io.aledep10.obsidiansync.orchestrator.EventType;
import io.aledep10.obsidiansync.orchestrator.SyncEvent;
import io.aledep10.obsidiansync.orchestrator.SyncEventQueue;
import io.aledep10.obsidiansync.orchestrator.SyncOrchestrator;
import io.aledep10.obsidiansync.scheduler.AutosaveScheduler;
import io.aledep10.obsidiansync.service.GitService;
import io.aledep10.obsidiansync.service.LogService;

import java.io.File;
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
public static void main(String[] args) {
    if (args == null || args.length < 2) {
        System.err.println("Usage: java -jar ObsidianSync.jar [pull|push|autosave] <properties_file>");
        System.exit(1);
    }

    Properties properties = new Properties();
    try {
        properties.load(new FileInputStream(new File(args[1])));
    } catch (IOException e) {
        System.err.println("Unable to load configuration file: " + args[1]);
        System.exit(1);
    }

    long interval = Long.parseLong(properties.getProperty("autosave.interval.minutes"));

    // ── Dependency wiring ────────────────────────────────────────────────
    LogService logService         = new LogService(properties);
    GitService gitService         = new GitService(properties, logService);
    SyncEventQueue queue          = new SyncEventQueue(logService);
    NotificationHook hook         = new LogNotificationHook(logService);
    SyncOrchestrator orchestrator = new SyncOrchestrator(queue, gitService, hook, logService);
    AutosaveScheduler scheduler   = new AutosaveScheduler(queue, logService, interval);

    // ── Shutdown hook — registered before start, order matters ──────────
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        scheduler.stop();     // stop publishing first
        orchestrator.stop();  // then drain and stop consuming
    }, "obsidiansync-shutdown"));

    // ── CLI argument → event ─────────────────────────────────────────────
    switch (args[0]) {
        case "pull"     -> queue.publish(new SyncEvent(EventType.PULL_LOGON));
        case "push"     -> queue.publish(new SyncEvent(EventType.PUSH_LOGOFF));
        case "autosave" -> { /* AutosaveScheduler handles periodic publishing */ }
        case null, default -> {
            logService.error("Unknown operation: " + args[0]);
            logService.error("Usage: java -jar ObsidianSync.jar [pull|push|autosave] <properties_file>");
            System.exit(1);
        }
    }

    // ── Start — scheduler first, then orchestrator (blocks on worker.join) ──
    scheduler.start();
    orchestrator.start();
}