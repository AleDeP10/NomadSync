package io.aledep10.nomadsync.scheduler;

import io.aledep10.nomadsync.Main;
import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.orchestrator.SyncEvent;
import io.aledep10.nomadsync.orchestrator.SyncEventQueue;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.orchestrator.SyncOrchestrator;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodically publishes {@link EventType#AUTOSAVE} events to the broadcast queue.
 *
 * <p>Acts exclusively as a publisher — never interacts with {@link io.aledep10.nomadsync.service.GitService}
 * directly. Sequencing and execution are delegated to {@link SyncOrchestrator}.</p>
 *
 * <h2>Broadcast model</h2>
 * <p>Publishes {@code AUTOSAVE} events with {@code vaultId = null} — the broadcast
 * sentinel. The broadcaster thread in {@link Main} consumes from this queue and
 * re-publishes the event to all per-vault queues.</p>
 *
 * <p>Uses {@link ScheduledExecutorService#scheduleAtFixedRate} to model autosave
 * as a fixed-interval clock. The first execution is delayed by one full interval —
 * at logon a {@code PULL_LOGON} event is already in flight and an immediate autosave
 * would be redundant.</p>
 */
public class AutosaveScheduler {

    private final SyncEventQueue broadcastQueue;
    private final LogService logService;
    private final long interval;
    private final TimeUnit timeUnit;
    private final ScheduledExecutorService executor;

    private ScheduledFuture<?> scheduledTask;

    /**
     * Production constructor. Interval is expressed in minutes.
     *
     * @param broadcastQueue  the broadcast queue — events published here with
     *                        {@code vaultId = null} are fanned out by the broadcaster
     *                        thread in {@link Main} to all per-vault queues
     * @param logService      shared logging service
     * @param intervalMinutes interval between autosave events in minutes
     */
    public AutosaveScheduler(SyncEventQueue broadcastQueue, LogService logService,
                             long intervalMinutes) {
        this(broadcastQueue, logService, intervalMinutes, TimeUnit.MINUTES);
    }

    /**
     * Package-private constructor for testing purposes only.
     *
     * <p>Allows tests to use short intervals (e.g. milliseconds) without
     * changing the public interface used by {@link Main}.</p>
     *
     * @param broadcastQueue the broadcast queue
     * @param logService     shared logging service
     * @param interval       interval value
     * @param timeUnit       time unit for the interval
     */
    AutosaveScheduler(SyncEventQueue broadcastQueue, LogService logService,
                      long interval, TimeUnit timeUnit) {
        this.broadcastQueue = broadcastQueue;
        this.logService     = logService;
        this.interval       = interval;
        this.timeUnit       = timeUnit;
        this.executor       = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Starts the autosave timer.
     *
     * <p>The task is wrapped in try/catch to prevent unchecked exceptions
     * from silently cancelling the schedule.</p>
     */
    public void start() {
        logService.info("AutosaveScheduler: starting, interval = " + interval + " " + timeUnit);
        scheduledTask = executor.scheduleAtFixedRate(() -> {
            try {
                SyncEvent event = new SyncEvent(EventType.AUTOSAVE, null);
                logService.info("AutosaveScheduler: publishing " + event);
                broadcastQueue.publish(event);
            } catch (Exception e) {
                logService.error("AutosaveScheduler: unexpected error during publish: "
                        + e.getMessage());
            }
        }, interval, interval, timeUnit);
    }

    /**
     * Stops the autosave timer gracefully.
     *
     * <p>Should be called before {@link SyncOrchestrator#stop()} to avoid
     * publishing events onto an unattended queue.</p>
     */
    public void stop() {
        logService.info("AutosaveScheduler: shutdown requested.");
        if (scheduledTask != null) scheduledTask.cancel(false);
        executor.shutdown();
        try {
            if (executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logService.info("AutosaveScheduler: stopped cleanly.");
            } else {
                logService.error("AutosaveScheduler: shutdown timed out, forcing stop.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            logService.error("AutosaveScheduler: interrupted during shutdown: " + e.getMessage());
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}