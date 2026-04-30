package io.aledep10.obsidiansync.orchestrator;

/**
 * Represents a synchronization command published to the {@link SyncEventQueue}.
 *
 * <p>Instances are immutable after construction, with the exception of retry state
 * which is updated by {@link SyncOrchestrator} during exponential backoff cycles.</p>
 *
 * <p>Implements {@link Comparable} so that {@link java.util.concurrent.PriorityBlockingQueue}
 * can order events by priority. When two events share the same priority, the older one
 * (lower timestamp) is processed first.</p>
 */
public class SyncEvent implements Comparable<SyncEvent> {

    private final EventType type;
    private final long timestamp;

    private int retryCount;
    private long retryDelay;

    /** Initial retry delay in milliseconds (30 seconds). */
    public static final long INITIAL_RETRY_DELAY_MS = 30_000L;

    /**
     * Constructs a new SyncEvent with the current timestamp and zeroed retry state.
     *
     * @param type the type of synchronization operation to perform
     */
    public SyncEvent(EventType type) {
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.retryCount = 0;
        this.retryDelay = INITIAL_RETRY_DELAY_MS;
    }

    /**
     * Package-private constructor for testing purposes only.
     *
     * <p>Allows tests to create {@link SyncEvent} instances with a controlled
     * timestamp, enabling deterministic latest-wins scenarios in
     * io.aledep10.obsidiansync.orchestrator.SyncEventQueueTest
     * without exposing timestamp mutability to production code.</p>
     *
     * <p>Prefer this constructor over setTimestamp(long) — it keeps
     * the timestamp immutable after construction and removes the need for
     * {@link Thread#sleep} to guarantee distinct timestamps.</p>
     *
     * @param type      the type of synchronization operation
     * @param timestamp epoch milliseconds to assign as the event timestamp
     */
    SyncEvent(EventType type, long timestamp) {
        this.type      = type;
        this.timestamp = timestamp;
        this.retryCount = 0;
        this.retryDelay = INITIAL_RETRY_DELAY_MS;
    }

    /**
     * Increments the retry counter and doubles the retry delay (exponential backoff).
     *
     * <p>Delay progression: 30s → 60s → 120s.</p>
     */
    public void incrementRetry() {
        this.retryCount++;
        this.retryDelay *= 2;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public EventType getType() { return type; }

    public long getTimestamp() { return timestamp; }

    public int getRetryCount() { return retryCount; }

    public long getRetryDelay() { return retryDelay; }

    // ── Comparable ───────────────────────────────────────────────────────────

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
                other.type.getPriority()
        );
        if (priorityComparison != 0) {
            return priorityComparison;
        }
        return Long.compare(this.timestamp, other.timestamp);
    }

    @Override
    public String toString() {
        return "SyncEvent{type=%s, retryCount=%d, retryDelay=%dms}"
                .formatted(type, retryCount, retryDelay);
    }
}