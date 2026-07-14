package io.aledep10.nomadsync.orchestrator;

import io.aledep10.nomadsync.exception.GitException;
import io.aledep10.nomadsync.exception.NetworkException;
import io.aledep10.nomadsync.exception.VaultException;
import io.aledep10.nomadsync.hook.NotificationHook;
import io.aledep10.nomadsync.service.GitService;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.util.StringUtil;
import io.aledep10.nomadsync.vault.Vault;

import java.time.LocalDateTime;

/**
 * Coordinates sync operations for a single {@link Vault} by consuming events from
 * {@link SyncEventQueue} and delegating execution to {@link GitService}.
 *
 * <h2>Threading model</h2>
 * <p>Runs a dedicated worker thread ({@code nomadsync-worker}) that processes
 * events serially, preventing Git concurrency issues by design. {@link #start()}
 * blocks the calling thread until the worker terminates — callers must run it on
 * a dedicated thread (see {@code Main}).</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Construct with all dependencies — worker thread is prepared but not started.</li>
 *   <li>Call {@link #start()} — starts the worker and blocks until it stops.</li>
 *   <li>Call {@link #stop()} from another thread (e.g. shutdown hook) to signal
 *       the worker to finish its current task and exit cleanly.</li>
 * </ol>
 *
 * <h2>Error handling</h2>
 * <ul>
 *   <li>{@link NetworkException} → exponential backoff retry up to
 *       {@link #MAX_RETRIES} attempts, then {@link NotificationHook#onFailure}.</li>
 *   <li>{@link GitException} → immediate {@link NotificationHook#onFailure},
 *       no retry — local Git errors are not transient.</li>
 *   <li>{@link VaultException} → logged at {@code WARN} and swallowed — snapshot/
 *       conflict failures are non-blocking; sync continues.</li>
 * </ul>
 *
 * <h2>Constructor argument order</h2>
 * <p>Follows the project convention: domain object first ({@link Vault}),
 * then services in descending order of complexity, {@link LogService} last
 * among services, then infrastructure ({@link SyncEventQueue},
 * {@link NotificationHook}).</p>
 *
 * <h2>Logging conventions</h2>
 * <p>The {@code logService} instance injected here is already vault-scoped
 * (via {@code LogService#withVault}, applied by the caller before construction) —
 * individual log lines do not need to repeat the vault identity, unlike the
 * stateless multi-vault services ({@link GitService}, {@link
 * io.aledep10.nomadsync.service.VaultService}).</p>
 * <p>{@link #execute(SyncEvent)} emits a single {@code INFO} line announcing the
 * event at the start; which branch was taken and its outcome (nothing to commit,
 * no changes detected, completion) are logged at {@code DEBUG} — the intro line
 * is the single point of "this happened" observability, per the project's
 * logging conventions. {@link #stop()} follows the same pattern: its own
 * {@code INFO} intro announces the shutdown request, while the worker's
 * acknowledgement of the resulting interrupt is {@code DEBUG} detail, not a
 * second intro. A caught {@link VaultException} and an interruption that reaches
 * {@link #start()} outside the normal {@link #stop()} path are both anomalies,
 * not ordinary outcomes, and are logged at {@code WARN} accordingly.</p>
 */
public class SyncOrchestrator {

    public static final int MAX_RETRIES = 3;

    private final Vault vault;
    private final SyncEventQueue queue;
    private final GitService gitService;
    private final NotificationHook notificationHook;
    private final LogService logService;
    private final Thread worker;

    /**
     * Constructs the orchestrator and prepares the worker thread.
     * The worker is not started until {@link #start()} is called.
     *
     * @param vault            the vault this orchestrator manages — used to derive
     *                         the vault path and identity for all Git operations
     * @param gitService       stateless Git operations delegate
     * @param logService       vault-scoped logging service
     * @param queue            priority event queue — the orchestrator is the sole consumer
     * @param notificationHook hook invoked on unrecoverable failures
     */
    public SyncOrchestrator(Vault vault,
                            GitService gitService, LogService logService,
                            SyncEventQueue queue, NotificationHook notificationHook) {
        this.vault             = vault;
        this.queue             = queue;
        this.gitService        = gitService;
        this.notificationHook  = notificationHook;
        this.logService        = logService;

        this.worker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    SyncEvent event = queue.consume();
                    execute(event);
                } catch (InterruptedException e) {
                    // Acknowledgement of the interrupt() sent by stop() — stop()
                    // already logged its own INFO intro; this is outcome detail.
                    logService.debug("Worker interrupted, shutting down.");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "nomadsync-worker");
    }

    /**
     * Starts the worker thread and blocks the calling thread until it terminates.
     *
     * <p>Must be called on a dedicated thread — it will block until {@link #stop()}
     * is called from another thread (e.g. the JVM shutdown hook registered in
     * {@code Main}).</p>
     *
     * <p>Logging: an interruption reaching this method directly (i.e. not via the
     * normal {@link #stop()} path) is an anomalous, unexpected condition and is
     * logged at {@code WARN}.</p>
     */
    public void start() {
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            logService.warn("Main thread interrupted while waiting for worker");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Signals the worker to stop and waits for it to finish the current task.
     *
     * <p>Uses {@code interrupt()} to unblock {@link SyncEventQueue#consume()}
     * (which internally calls {@link java.util.concurrent.PriorityBlockingQueue#take()})
     * and {@code join()} to wait for clean termination — never kills the thread
     * mid-operation. The current task completes before the worker exits.</p>
     *
     * <p>Logging: a single {@code INFO} line announces the shutdown request at
     * the start — the worker's own acknowledgement of the resulting interrupt is
     * logged at {@code DEBUG}, not as a second intro.</p>
     */
    public void stop() {
        logService.info("Shutdown requested - waiting for worker to finish.");
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
     *   <li>{@link EventType#PULL_LOGON} — stash if dirty → pull → stash pop.
     *       The stash/pop is skipped entirely if the working tree is clean.</li>
     *   <li>{@link EventType#SYNCHRONIZE} — delegates to
     *       {@link GitService#synchronize(Vault)}: commit local if dirty → pull →
     *       on conflict: FIFO backup + pull {@code -X ours} +
     *       remote-conflicts snapshot → push.</li>
     *   <li>{@link EventType#PUSH_LOGOFF} — commit local (if dirty) → push.</li>
     *   <li>{@link EventType#COMMIT_MANUAL} — commit local with user-provided message
     *       (from {@link SyncEvent#getMessage()}) if dirty; no-op otherwise. Never
     *       pushes. If the message is blank, a timestamped fallback is used.</li>
     *   <li>{@link EventType#AUTOSAVE} — commit local with auto-generated timestamp
     *       message if dirty; no-op otherwise. Never pushes.</li>
     * </ul>
     *
     * <p>Logging: a single {@code INFO} line announces the event at the start.
     * Which branch was taken, whether it turned out to be a no-op, and the final
     * completion are all logged at {@code DEBUG} — the intro line is the single
     * point of observability for "this event ran"; a caught {@link VaultException}
     * is the one exception to this, logged at {@code WARN} as an anomaly rather
     * than ordinary outcome detail, since it represents a real failure that is
     * deliberately swallowed rather than a normal branch outcome.</p>
     *
     * @param event the event to process
     * @throws InterruptedException if the thread is interrupted during execution
     */
    public void execute(SyncEvent event) throws InterruptedException {
        try {
            logService.info("-> Performing " + event);
            switch (event.getType()) {
                case PULL_LOGON -> {
                    boolean dirty = gitService.hasUncommittedChanges(vault);
                    if (dirty) gitService.stash(vault);
                    gitService.pull(vault);
                    if (dirty) gitService.stashPop(vault);
                }
                case SYNCHRONIZE -> gitService.synchronize(vault);
                case PUSH_LOGOFF -> {
                    int commitExit = gitService.commitLocal(vault, "push " + LocalDateTime.now());
                    if (commitExit != 0) {
                        logService.debug("Nothing to commit, pushing existing commits.");
                    }
                    gitService.push(vault);
                }
                case COMMIT_MANUAL -> {
                    if (gitService.hasUncommittedChanges(vault)) {
                        String message = event.getMessage();
                        if (StringUtil.isBlank(message)) {
                            logService.warn("COMMIT_MANUAL: empty message, using fallback");
                            message = "manual commit " + LocalDateTime.now();
                        }
                        gitService.commitLocal(vault, message);
                    } else {
                        logService.debug("No changes detected, skipping manual commit.");
                    }
                }
                case AUTOSAVE -> {
                    if (gitService.hasUncommittedChanges(vault)) {
                        gitService.commitLocal(vault, "autosave " + LocalDateTime.now());
                    } else {
                        logService.debug("No changes detected, skipping autosave.");
                    }
                }
            }
            logService.debug("-> " + event + " completed");
        } catch (VaultException e) {
            // Snapshot/conflict failure is non-blocking — logged as an anomaly
            // (WARN), then swallowed; sync continues.
            logService.warn("-> " + event + " failed: " + e.getMessage());
        } catch (NetworkException e) {
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
     * <p>Delay progression: 30s → 60s → 120s. After {@link #MAX_RETRIES} attempts
     * the event is discarded and {@link NotificationHook#onFailure} is invoked.</p>
     *
     * <p>Called only for {@link NetworkException} — {@link GitException} bypasses
     * retry and fails immediately in {@link #execute}.</p>
     *
     * <p>If the thread is interrupted during the sleep, the notification hook is
     * invoked, the interrupt flag is restored, and the {@link InterruptedException}
     * is rethrown — the worker loop will catch it and shut down cleanly.</p>
     *
     * <p>Logging: unaffected by the intro/outro convention — every line here
     * ({@code WARN} per attempt, {@code ERROR} on final discard) reports an
     * actual anomaly, not routine flow.</p>
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