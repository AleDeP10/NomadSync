package io.aledep10.nomadsync.vault;

import io.aledep10.nomadsync.orchestrator.SyncEvent;
import io.aledep10.nomadsync.orchestrator.SyncEventQueue;
import io.aledep10.nomadsync.orchestrator.SyncOrchestrator;
import io.aledep10.nomadsync.service.GitService;

import java.util.concurrent.ScheduledFuture;

/**
 * Runtime context for a registered vault — groups the static {@link Vault}
 * configuration with its mutable execution state.
 *
 * <p>Uses composition over inheritance: {@link Vault} is a pure value object
 * (loaded from JSON, no execution concerns); {@link VaultContext} wraps it with
 * the per-vault queue, orchestrator, and aggregator future needed at runtime.</p>
 *
 * <h2>Why a record?</h2>
 * <p>All four components are set at construction and never replaced — the context
 * is created once per vault and lives until shutdown. A record makes immutability
 * explicit and eliminates boilerplate getters.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Created at startup (or when a vault is registered via the tray) for each
 * entry in {@code vaults.json}. The orchestrator is started immediately after
 * construction. At shutdown, {@link #aggregatorFuture()} is cancelled to
 * interrupt the orchestrator's worker thread cleanly.</p>
 *
 * <h2>Threading</h2>
 * <p>The {@link SyncOrchestrator} runs its own worker thread that consumes from
 * the per-vault {@link SyncEventQueue} and delegates to {@link GitService}.
 * One orchestrator per vault guarantees serial Git execution per vault while
 * allowing concurrent execution across vaults.</p>
 *
 * @param vault            static vault configuration loaded from {@code vaults.json}
 * @param queue            per-vault priority queue for incoming {@link SyncEvent}s
 * @param orchestrator     per-vault orchestrator — consumes from {@code queue}
 *                         and delegates to {@link GitService}
 * @param aggregatorFuture future handle used to cancel the orchestrator at shutdown
 *                         via {@link ScheduledFuture#cancel(boolean)}
 */
public record VaultContext(
        Vault vault,
        SyncEventQueue queue,
        SyncOrchestrator orchestrator,
        ScheduledFuture<?> aggregatorFuture) {}