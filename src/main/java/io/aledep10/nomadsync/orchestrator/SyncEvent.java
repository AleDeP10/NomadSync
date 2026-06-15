package io.aledep10.nomadsync.orchestrator;

import io.aledep10.nomadsync.scheduler.AutosaveScheduler;
import io.aledep10.nomadsync.tray.SocketServer;

/**
 * Represents a synchronization command published to the {@link SyncEventQueue}.
 *
 * <p>Instances are immutable after construction, with the exception of retry state
 * which is updated by {@link SyncOrchestrator} during exponential backoff cycles.</p>
 *
 * <p>Implements {@link Comparable} so that {@link java.util.concurrent.PriorityBlockingQueue}
 * can order events by priority. When two events share the same priority, the older one
 * (lower timestamp) is processed first.</p>
 *
 * <h2>Broadcast events</h2>
 * <p>An event constructed with {@code vaultId = null} is a <em>broadcast sentinel</em> —
 * it targets all registered vaults. {@link SocketServer}
 * expands it into one event per vault via {@link #forVault(String)} before publishing
 * to the per-vault queues. Used by {@link AutosaveScheduler}
 * to trigger autosave across all vaults without knowing the vault list.</p>
 */
public class SyncEvent implements Comparable<SyncEvent> {

    /** Initial retry delay in milliseconds (30 seconds). */
    public static final long INITIAL_RETRY_DELAY_MS = 30_000L;

    private final EventType type;
    private final String vaultId;
    private final long timestamp;

    private int retryCount;
    private long retryDelay;

    /**
     * Constructs a new SyncEvent with the current timestamp and zeroed retry state.
     *
     * @param type    the type of synchronization operation to perform
     * @param vaultId the target vault identifier, or {@code null} for broadcast events
     */
    public SyncEvent(EventType type, String vaultId) {
        this.type       = type;
        this.vaultId    = vaultId;
        this.timestamp  = System.currentTimeMillis();
        this.retryCount = 0;
        this.retryDelay = INITIAL_RETRY_DELAY_MS;
    }

    /**
     * Package-private constructor for testing purposes only.
     *
     * <p>Allows tests to create {@link SyncEvent} instances with a controlled
     * timestamp, enabling deterministic latest-wins scenarios in
     * {@link SyncEventQueueTest} without exposing timestamp mutability to
     * production code.</p>
     *
     * @param type               the type of synchronization operation
     * @param vaultId            the target vault identifier, or {@code null} for broadcast
     * @param timestamp          epoch milliseconds to assign as the event timestamp
     * @param initialRetryDelay  initial retry delay in milliseconds
     */
    SyncEvent(EventType type, String vaultId, long timestamp, long initialRetryDelay) {
        this.type       = type;
        this.vaultId    = vaultId;
        this.timestamp  = timestamp;
        this.retryCount = 0;
        this.retryDelay = initialRetryDelay;
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

    /**
     * Increments the retry counter and doubles the retry delay (exponential backoff).
     *
     * <p>Delay progression: 30s → 60s → 120s.</p>
     */
    public void incrementRetry() {
        this.retryCount++;
        this.retryDelay *= 2;
    }

    // ── Broadcast support ─────────────────────────────────────────────────────

    /**
     * Creates a vault-specific copy of this broadcast event.
     *
     * <p>Used by {@link SocketServer} to expand a
     * broadcast sentinel ({@code vaultId = null}) into one targeted event per
     * registered vault. Preserves the original timestamp so priority ordering
     * is consistent across the expanded events.</p>
     *
     * @param vaultId the target vault identifier to assign
     * @return a new {@link SyncEvent} identical to this one but with the given vaultId
     * @throws UnsupportedOperationException if this event already has a vaultId assigned
     */
    public SyncEvent forVault(String vaultId) {
        if (this.vaultId != null) {
            throw new UnsupportedOperationException(
                    "Event already assigned to vault: " + this.vaultId);
        }
        return new SyncEvent(type, vaultId, timestamp, retryDelay);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public EventType getType()      { return type; }
    public String    getVaultId()   { return vaultId; }
    public long      getTimestamp() { return timestamp; }
    public int       getRetryCount(){ return retryCount; }
    public long      getRetryDelay(){ return retryDelay; }

    // ── Comparable ────────────────────────────────────────────────────────────

    /**
     * Compares events by priority first, then by timestamp for same-priority events.
     *
     * @param other the event to compare against
     * @return negative if this event has higher urgency, positive otherwise
     */
    @Override
    public int compareTo(SyncEvent other) {
        int priorityComparison = Integer.compare(
                this.type.getPriority(),
                other.type.getPriority());
        if (priorityComparison != 0) return priorityComparison;
        return Long.compare(this.timestamp, other.timestamp);
    }

    @Override
    public String toString() {
        return "SyncEvent{type=%s, vaultId=%s, retryCount=%d, retryDelay=%dms}"
                .formatted(type, vaultId, retryCount, retryDelay);
    }
}
