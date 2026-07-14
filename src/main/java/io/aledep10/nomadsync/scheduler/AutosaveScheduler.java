package io.aledep10.nomadsync.scheduler;

import io.aledep10.nomadsync.Main;
import io.aledep10.nomadsync.config.NomadProperties;
import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.orchestrator.SyncEvent;
import io.aledep10.nomadsync.orchestrator.SyncEventQueue;
import io.aledep10.nomadsync.orchestrator.SyncOrchestrator;
import io.aledep10.nomadsync.service.LogService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodically publishes {@link EventType#AUTOSAVE} events to the broadcast queue.
 *
 * <p>Acts exclusively as a publisher — never interacts with
 * {@link io.aledep10.nomadsync.service.GitService} directly. Sequencing and
 * execution are delegated to {@link SyncOrchestrator} via the broadcast queue.</p>
 *
 * <h2>Broadcast model</h2>
 * <p>Publishes {@code AUTOSAVE} events with {@code vaultId = null} — the broadcast
 * sentinel. The broadcaster thread in {@link Main} consumes from this queue and
 * re-publishes the event to all per-vault queues via
 * {@link SyncEvent#forVault(String)}.</p>
 *
 * <h2>Schedule</h2>
 * <p>Uses {@link ScheduledExecutorService#scheduleAtFixedRate} to model autosave as
 * a fixed-interval clock. The first execution is delayed by one full interval —
 * at logon a {@code PULL_LOGON} event is already in flight, making an immediate
 * autosave redundant. The interval is configured via
 * {@link NomadProperties.Autosave#INTERVAL_MINUTES} (default: {@code 15}).</p>
 *
 * <h2>Error isolation</h2>
 * <p>The scheduled task wraps its body in {@code try/catch} — any unchecked exception
 * is logged to {@link LogService} and the schedule continues. Without this guard,
 * a single runtime exception would silently cancel all future executions.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Call {@link #start()} to begin the schedule. Call {@link #stop()} before
 * {@link SyncOrchestrator#stop()} to avoid publishing events onto an unattended
 * queue during shutdown.</p>
 *
 * <h2>Constructor argument order</h2>
 * <p>Follows the project convention: infrastructure first ({@link SyncEventQueue}),
 * then {@link LogService}, then configuration values.</p>
 */
public class AutosaveScheduler {

    private final SyncEventQueue          broadcastQueue;
    private final LogService              logService;
    private final long                    interval;
    private final TimeUnit                timeUnit;
    private final ScheduledExecutorService executor;

    private ScheduledFuture<?> scheduledTask;

    /**
     * Production constructor — interval expressed in minutes.
     *
     * @param broadcastQueue  the broadcast queue; events published here with
     *                        {@code vaultId = null} are fanned out by the broadcaster
     *                        thread in {@link Main} to all per-vault queues
     * @param logService      shared logging service
     * @param intervalMinutes interval between autosave events, in minutes;
     *                        read from {@link NomadProperties.Autosave#INTERVAL_MINUTES}
     *                        by {@code Main}
     */
    public AutosaveScheduler(SyncEventQueue broadcastQueue, LogService logService,
                             long intervalMinutes) {
        this(broadcastQueue, logService, intervalMinutes, TimeUnit.MINUTES);
    }

    /**
     * Package-private constructor for testing purposes only.
     *
     * <p>Allows tests to specify short intervals (e.g. milliseconds) without
     * changing the public interface used by {@link Main}.</p>
     *
     * @param broadcastQueue the broadcast queue
     * @param logService     shared logging service
     * @param interval       interval value
     * @param timeUnit       time unit for {@code interval}
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
     * Starts the autosave schedule.
     *
     * <p>The first execution is delayed by one full interval — see class-level
     * Javadoc for rationale. The task body is wrapped in {@code try/catch} so that
     * unchecked exceptions do not silently cancel the schedule.</p>
     */
    public void start() {
        logService.info("AutosaveScheduler: starting, interval = " + interval + " " + timeUnit);
        scheduledTask = executor.scheduleAtFixedRate(() -> {
            try {
                SyncEvent event = new SyncEvent(EventType.AUTOSAVE, null);
                logService.info("AutosaveScheduler: publishing " + event);
                broadcastQueue.publish(event);
            } catch (Exception e) {
                logService.error("AutosaveScheduler - runtime error during publish: "
                        + e.getMessage());
            }
        }, interval, interval, timeUnit);
    }

    /**
     * Stops the autosave schedule gracefully.
     *
     * <p>Cancels the scheduled task without interrupting any in-progress execution
     * ({@code cancel(false)}), shuts down the executor, and waits up to 5 seconds
     * for clean termination. Forces shutdown if the timeout expires.</p>
     *
     * <p>Must be called before {@link SyncOrchestrator#stop()} to avoid publishing
     * {@code AUTOSAVE} events onto queues that are no longer being consumed.</p>
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
            logService.error("AutosaveScheduler - interrupted during shutdown: " + e.getMessage());
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}