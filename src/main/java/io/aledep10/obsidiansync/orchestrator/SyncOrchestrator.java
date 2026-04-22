package io.aledep10.obsidiansync.orchestrator;

import io.aledep10.obsidiansync.hook.NotificationHook;
import io.aledep10.obsidiansync.service.GitService;
import io.aledep10.obsidiansync.service.LogService;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Coordinates sync operations by consuming events from {@link SyncEventQueue}
 * and delegating execution to {@link GitService}.
 *
 * <p>Runs a dedicated worker thread that processes events serially,
 * preventing Git concurrency issues by design.</p>
 *
 * <p>On failure, applies exponential backoff retry up to {@link #MAX_RETRIES}
 * attempts before delegating to {@link NotificationHook#onFailure}.</p>
 */
public class SyncOrchestrator {

    public static final int MAX_RETRIES = 3;

    private final SyncEventQueue queue;
    private final GitService gitService;
    private final NotificationHook notificationHook;
    private final LogService logService;
    private final Thread worker;

    /**
     * Constructs the orchestrator and prepares the worker thread.
     * The worker is not started until {@link #start()} is called.
     */
    public SyncOrchestrator(SyncEventQueue queue, GitService gitService,
                            NotificationHook notificationHook, LogService logService) {
        this.queue            = queue;
        this.gitService       = gitService;
        this.notificationHook = notificationHook;
        this.logService       = logService;

        this.worker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    SyncEvent event = queue.consume();
                    execute(event);
                } catch (InterruptedException e) {
                    // consume() was unblocked by interrupt — clean exit
                    logService.info("Worker interrupted, shutting down.");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "obsidiansync-worker");
    }

    /**
     * Starts the worker thread and registers a JVM shutdown hook.
     * Blocks the calling thread until the worker terminates.
     */
    public void start() {
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            logService.error("Main thread interrupted while waiting for worker: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Signals the worker to stop and waits for it to finish the current task.
     *
     * <p>Uses {@code interrupt()} to unblock {@link SyncEventQueue#consume()}
     * and {@code join()} to wait for clean termination — never kills the thread
     * mid-operation.</p>
     */
    public void stop() {
        logService.info("Shutdown requested — waiting for worker to finish.");
        worker.interrupt();
        try {
            worker.join();
        } catch (InterruptedException e) {
            logService.error("Interrupted while waiting for worker to stop: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Dispatches a {@link SyncEvent} to the appropriate Git workflow.
     *
     * @param event the event to process
     * @throws InterruptedException if the thread is interrupted during execution
     */
    public void execute(SyncEvent event) throws InterruptedException {
        try {
            logService.info("-> Performing " + event);
            switch (event.getType()) {
                case PULL_LOGON -> {
                    boolean dirty = gitService.hasUncommittedChanges();
                    if (dirty) gitService.stash();
                    gitService.pull();
                    if (dirty) gitService.stashPop();
                }
                case PUSH_MANUAL, PUSH_LOGOFF -> {
                    int commitExit = gitService.commitLocal("push " + LocalDateTime.now());
                    if (commitExit != 0) {
                        logService.info("Nothing to commit, skipping push.");
                        return;
                    }
                    gitService.push();
                }
                case AUTOSAVE -> {
                    if (gitService.hasChanges()) {
                        gitService.commitLocal("autosave " + LocalDateTime.now());
                    } else {
                        logService.info("No changes detected, skipping autosave.");
                    }
                }
            }
            logService.info("-> " + event + " completed");
        } catch (IOException e) {
            logService.error("I/O error while performing " + event + ": " + e.getMessage());
            retry(event, e);
        }
    }

    /**
     * Retries a failed event using exponential backoff.
     *
     * <p>Delay progression: 30s → 60s → 120s.
     * After {@link #MAX_RETRIES} attempts the event is discarded
     * and {@link NotificationHook#onFailure} is invoked.</p>
     *
     * @param event     the event to retry
     * @param cause     the exception that caused the failure
     * @throws InterruptedException if the thread is interrupted during the delay
     */
    private void retry(SyncEvent event, Exception cause) throws InterruptedException {
        if (event.getRetryCount() < MAX_RETRIES) {
            event.incrementRetry();
            logService.warn("Retry %d/%d for %s in %ds".formatted(
                    event.getRetryCount(), MAX_RETRIES,
                    event.getType(), event.getRetryDelay() / 1000));
            try {
                Thread.sleep(event.getRetryDelay());
            } catch (InterruptedException e) {
                notificationHook.onFailure(event, "Retry interrupted: " + cause.getMessage());
                Thread.currentThread().interrupt();
                throw e;
            }
            queue.publish(event);
        } else {
            notificationHook.onFailure(event, "Failed after " + MAX_RETRIES + " retries: " + cause.getMessage());
            logService.error("Discarded after max retries: " + event);
        }
    }
}