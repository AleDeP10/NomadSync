package io.aledep10.nomadsync.orchestrator;

import io.aledep10.nomadsync.exception.GitException;
import io.aledep10.nomadsync.exception.NetworkException;
import io.aledep10.nomadsync.exception.VaultException;
import io.aledep10.nomadsync.hook.NotificationHook;
import io.aledep10.nomadsync.service.GitService;
import io.aledep10.nomadsync.service.LogService;

import java.time.LocalDateTime;
import java.util.Properties;

/**
 * Coordinates sync operations by consuming events from {@link SyncEventQueue}
 * and delegating execution to {@link GitService}.
 *
 * <p>Runs a dedicated worker thread that processes events serially,
 * preventing Git concurrency issues by design.</p>
 *
 * <p>On {@link NetworkException}, applies exponential backoff retry up to
 * {@link #MAX_RETRIES} attempts before invoking {@link NotificationHook#onFailure}.
 * On {@link GitException}, fails immediately — local Git errors are not transient
 * and do not benefit from retry.</p>
 */
public class SyncOrchestrator {

    public static final int MAX_RETRIES = 3;

    private final String vaultPath;
    private final SyncEventQueue queue;
    private final GitService gitService;
    private final NotificationHook notificationHook;
    private final LogService logService;
    private final Thread worker;

    /**
     * Constructs the orchestrator and prepares the worker thread.
     * The worker is not started until {@link #start()} is called.
     *
     * @param properties      application properties — must contain {@code vault.path}
     * @param gitService      stateless Git operations delegate
     * @param logService      shared logging service
     * @param queue           priority event queue
     * @param notificationHook hook invoked on unrecoverable failures
     */
    public SyncOrchestrator(Properties properties,
                            GitService gitService, LogService logService,
                            SyncEventQueue queue, NotificationHook notificationHook) {
        this.vaultPath          = properties.getProperty("vault.path");
        this.queue              = queue;
        this.gitService         = gitService;
        this.notificationHook   = notificationHook;
        this.logService         = logService;

        this.worker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    SyncEvent event = queue.consume();
                    execute(event);
                } catch (InterruptedException e) {
                    logService.info("Worker interrupted, shutting down.");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "obsidiansync-worker");
    }

    /**
     * Starts the worker thread and blocks the calling thread until it terminates.
     */
    public void start() {
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            logService.info("Main thread interrupted while waiting for worker");
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
     * <h2>Dispatch table</h2>
     * <ul>
     *   <li>{@link EventType#PULL_LOGON} — stash if dirty → pull → stash pop</li>
     *   <li>{@link EventType#SYNCHRONIZE} — delegates entirely to
     *       {@link GitService#synchronize(String)}: commit local if dirty → pull →
     *       on conflict: backup + pull -X ours + remote-conflicts snapshot → push</li>
     *   <li>{@link EventType#PUSH_LOGOFF} — commit local → push</li>
     *   <li>{@link EventType#AUTOSAVE} — commit local only if dirty, never pushes</li>
     * </ul>
     *
     * <h2>Error handling</h2>
     * <ul>
     *   <li>{@link NetworkException} → exponential backoff retry up to
     *       {@link #MAX_RETRIES} attempts, then {@link NotificationHook#onFailure}</li>
     *   <li>{@link GitException} → immediate {@link NotificationHook#onFailure},
     *       no retry</li>
     *   <li>{@link VaultException} → logged and swallowed — backup failure is
     *       non-blocking, sync proceeds</li>
     * </ul>
     *
     * @param event the event to process
     * @throws InterruptedException if the thread is interrupted during execution
     */
    public void execute(SyncEvent event) throws InterruptedException {
        try {
            logService.info("-> Performing " + event);
            switch (event.getType()) {
                case PULL_LOGON -> {
                    boolean dirty = gitService.hasUncommittedChanges(vaultPath);
                    if (dirty) gitService.stash(vaultPath);
                    gitService.pull(vaultPath);
                    if (dirty) gitService.stashPop(vaultPath);
                }
                case SYNCHRONIZE -> {
                    // [IN_REVIEW] procedura descritta dal DTR, refactoring gitService a cascata
                    gitService.synchronize(vaultPath);
                }
                case PUSH_LOGOFF -> {
                    int commitExit = gitService.commitLocal(vaultPath, "push " + LocalDateTime.now());
                    if (commitExit != 0) {
                        logService.info("Nothing to commit, pushing existing commits.");
                    }
                    gitService.push(vaultPath);
                }
                case AUTOSAVE -> {
                    if (gitService.hasUncommittedChanges(vaultPath)) {
                        gitService.commitLocal(vaultPath, "autosave " + LocalDateTime.now());
                    } else {
                        logService.info("No changes detected, skipping autosave.");
                    }
                }
            }
            logService.info("-> " + event + " completed");
        } catch (VaultException e) {
            // [NOTA] errore backup, non deve essere bloccante
            logService.info("-> " + event + " failed: " + e.getMessage());
        }
        catch (NetworkException e) {
            logService.error("Network error while performing " + event + ": " + e.getMessage());
            retry(event, e);
        } catch (GitException e) {
            logService.error("Git error while performing " + event + ": " + e.getMessage());
            notificationHook.onFailure(event, "Git error (no retry): " + e.getMessage());
        }
    }

    /**
     * Retries a failed event using exponential backoff.
     *
     * <p>Delay progression: 30s → 60s → 120s.
     * After {@link #MAX_RETRIES} attempts the event is discarded
     * and {@link NotificationHook#onFailure} is invoked.</p>
     *
     * <p>Called only for {@link NetworkException} — {@link GitException}
     * bypasses retry and fails immediately in {@link #execute}.</p>
     *
     * @param event the event to retry
     * @param cause the network exception that caused the failure
     * @throws InterruptedException if the thread is interrupted during the delay
     */
    private void retry(SyncEvent event, NetworkException cause) throws InterruptedException {
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
            notificationHook.onFailure(event,
                    "Network failure after " + MAX_RETRIES + " retries: " + cause.getMessage());
            logService.error("Discarded after max retries: " + event);
        }
    }
}
