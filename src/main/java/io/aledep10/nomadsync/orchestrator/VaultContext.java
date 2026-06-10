package io.aledep10.nomadsync.orchestrator;

import io.aledep10.nomadsync.service.GitService;
import io.aledep10.nomadsync.tray.SocketServer;

import java.util.concurrent.ScheduledFuture;

/**
 * Runtime context for a registered vault — groups the static {@link Vault}
 * configuration with its mutable execution state.
 *
 * <p>Uses composition over inheritance: {@link Vault} is a pure value object
 * (loaded from JSON, no execution concerns); {@link VaultContext} wraps it with
 * the per-vault queue, orchestrator, and aggregator future needed at runtime.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Created by {@link SocketServer#register(Vault)}
 * when a vault is registered. The orchestrator is started immediately via
 * {@link java.util.concurrent.ScheduledExecutorService#schedule} and cancelled
 * at shutdown via {@link #getAggregatorFuture()}.</p>
 *
 * <h2>Threading</h2>
 * <p>The {@link SyncOrchestrator} runs its own worker thread that consumes from
 * the per-vault {@link SyncEventQueue} and delegates to
 * {@link GitService}. One orchestrator per vault
 * guarantees serial Git execution per vault while allowing concurrent execution
 * across vaults.</p>
 */
public class VaultContext {

    private final Vault vault;
    private final SyncEventQueue queue;
    private final SyncOrchestrator orchestrator;
    private final ScheduledFuture<?> aggregatorFuture;

    /**
     * Constructs the vault runtime context.
     *
     * @param vault            static vault configuration loaded from {@code vaults.json}
     * @param queue            per-vault priority queue for incoming events
     * @param orchestrator     per-vault orchestrator — consumes from {@code queue}
     *                         and delegates to {@link GitService}
     * @param aggregatorFuture future handle used to cancel the orchestrator at shutdown
     *                         via {@link java.util.concurrent.ScheduledFuture#cancel(boolean)}
     */
    public VaultContext(Vault vault, SyncEventQueue queue,
                        SyncOrchestrator orchestrator, ScheduledFuture<?> aggregatorFuture) {
        this.vault            = vault;
        this.queue            = queue;
        this.orchestrator     = orchestrator;
        this.aggregatorFuture = aggregatorFuture;
    }

    /**
     * Returns the static vault configuration (id, name, path).
     *
     * @return the {@link Vault} value object
     */
    public Vault getVault() { return vault; }

    /**
     * Returns the per-vault event queue.
     *
     * @return the per-vault {@link SyncEventQueue}
     */
    public SyncEventQueue getQueue() { return queue; }

    /**
     * Returns the per-vault orchestrator.
     *
     * @return the {@link SyncOrchestrator} for this vault
     */
    public SyncOrchestrator getOrchestrator() { return orchestrator; }

    /**
     * Returns the {@link ScheduledFuture} used to cancel the orchestrator at shutdown.
     * Call {@code cancel(true)} to interrupt the orchestrator's worker thread.
     *
     * @return the aggregator future
     */
    public ScheduledFuture<?> getAggregatorFuture() { return aggregatorFuture; }
}
