package io.aledep10.obsidiansync.scheduler;

import io.aledep10.obsidiansync.orchestrator.EventType;
import io.aledep10.obsidiansync.orchestrator.SyncEvent;
import io.aledep10.obsidiansync.orchestrator.SyncEventQueue;
import io.aledep10.obsidiansync.service.LogService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodically publishes {@link EventType#AUTOSAVE} events to the {@link SyncEventQueue}.
 *
 * <p>Acts exclusively as a publisher — never interacts with {@link io.aledep10.obsidiansync.service.GitService}
 * directly. Sequencing and execution are delegated to {@link io.aledep10.obsidiansync.orchestrator.SyncOrchestrator}.</p>
 *
 * <p>Uses {@link java.util.concurrent.ScheduledExecutorService#scheduleAtFixedRate} to model
 * autosave as a fixed-interval clock — the interval is measured from the start of each
 * execution, not from its end.</p>
 */
public class AutosaveScheduler {

    private final SyncEventQueue queue;
    private final LogService logService;
    private final long intervalMinutes;
    private final ScheduledExecutorService executor;

    private ScheduledFuture<?> scheduledTask;

    /**
     * Constructs the scheduler. Does not start the timer.
     *
     * @param queue           the queue on which AUTOSAVE events are published
     * @param logService      shared logging service
     * @param intervalMinutes interval between autosave events in minutes
     */
    public AutosaveScheduler(SyncEventQueue queue, LogService logService, long intervalMinutes) {
        this.queue           = queue;
        this.logService      = logService;
        this.intervalMinutes = intervalMinutes;
        this.executor        = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Starts the autosave timer.
     *
     * <p>The first execution is delayed by one full interval — at logon,
     * a {@code PULL_LOGON} event is already published by {@link io.aledep10.obsidiansync.Main}.
     * An immediate autosave would be redundant.</p>
     *
     * <p>The task is wrapped in a try/catch to prevent unchecked exceptions from
     * silently cancelling the schedule — a known behaviour of {@code scheduleAtFixedRate}.</p>
     */
    public void start() {
        logService.info("AutosaveScheduler: starting, interval = " + intervalMinutes + " min");

        scheduledTask = executor.scheduleAtFixedRate(() -> {
            try {
                SyncEvent event = new SyncEvent(EventType.AUTOSAVE);
                logService.info("AutosaveScheduler: publishing " + event);
                queue.publish(event);
            } catch (Exception e) {
                logService.error("AutosaveScheduler: unexpected error during publish: " + e.getMessage());
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    /**
     * Stops the autosave timer gracefully.
     *
     * <p>Cancels the scheduled task, shuts down the executor, and waits up to 5 seconds
     * for termination. Should be called before {@link io.aledep10.obsidiansync.orchestrator.SyncOrchestrator#stop()}
     * to avoid publishing events onto an unattended queue.</p>
     */
    public void stop() {
        logService.info("AutosaveScheduler: shutdown requested.");

        if (scheduledTask != null) {
            scheduledTask.cancel(false);    // false = do not interrupt if running
        }

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