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
 * <p>Acts exclusively as a publisher — never interacts with GitService directly.
 * Sequencing and execution are delegated to SyncOrchestrator.</p>
 *
 * <p>Uses {@link ScheduledExecutorService#scheduleAtFixedRate} to model autosave
 * as a fixed-interval clock.</p>
 */
public class AutosaveScheduler {

    private final SyncEventQueue queue;
    private final LogService logService;
    private final long interval;
    private final TimeUnit timeUnit;
    private final ScheduledExecutorService executor;

    private ScheduledFuture<?> scheduledTask;

    /**
     * Production constructor. Interval is expressed in minutes.
     *
     * @param queue           the queue on which AUTOSAVE events are published
     * @param logService      shared logging service
     * @param intervalMinutes interval between autosave events in minutes
     */
    public AutosaveScheduler(SyncEventQueue queue, LogService logService, long intervalMinutes) {
        this(queue, logService, intervalMinutes, TimeUnit.MINUTES);
    }

    /**
     * Package-private constructor for testing purposes only.
     *
     * <p>Allows tests to use short intervals (e.g. milliseconds) without
     * changing the public interface used by {@link io.aledep10.obsidiansync.Main}.</p>
     *
     * @param queue      the queue on which AUTOSAVE events are published
     * @param logService shared logging service
     * @param interval   interval value
     * @param timeUnit   time unit for the interval
     */
    AutosaveScheduler(SyncEventQueue queue, LogService logService, long interval, TimeUnit timeUnit) {
        this.queue     = queue;
        this.logService = logService;
        this.interval  = interval;
        this.timeUnit  = timeUnit;
        this.executor  = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Starts the autosave timer.
     *
     * <p>The first execution is delayed by one full interval — at logon a
     * {@code PULL_LOGON} event is already published by Main.
     * An immediate autosave would be redundant.</p>
     *
     * <p>The task is wrapped in try/catch to prevent unchecked exceptions
     * from silently cancelling the schedule.</p>
     */
    public void start() {
        logService.info("AutosaveScheduler: starting, interval = " + interval + " " + TimeUnit.MINUTES);

        scheduledTask = executor.scheduleAtFixedRate(() -> {
            try {
                SyncEvent event = new SyncEvent(EventType.AUTOSAVE);
                logService.info("AutosaveScheduler: publishing " + event);
                queue.publish(event);
            } catch (Exception e) {
                logService.error("AutosaveScheduler: unexpected error during publish: " + e.getMessage());
            }
        }, interval, interval, timeUnit);
    }

    /**
     * Stops the autosave timer gracefully.
     *
     * <p>Should be called before {@link io.aledep10.obsidiansync.orchestrator.SyncOrchestrator#stop()}
     * to avoid publishing events onto an unattended queue.</p>
     */
    public void stop() {
        logService.info("AutosaveScheduler: shutdown requested.");

        if (scheduledTask != null) {
            scheduledTask.cancel(false);
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