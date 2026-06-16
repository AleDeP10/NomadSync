package io.aledep10.nomadsync.orchestrator;

/**
 * Defines the types of synchronization events that can be published to the {@link SyncEventQueue}.
 *
 * <p>Each type carries a numeric priority used by the queue to determine execution order.
 * Lower values mean higher urgency — {@code PULL_LOGON} and {@code SYNCHRONIZE} (1)
 * are always processed before {@code AUTOSAVE} (4).</p>
 *
 * <p>At equal priority, events are ordered by timestamp — the older event is processed first.</p>
 */
public enum EventType {

    /** Triggered at Windows logon. Precondition for all other operations. */
    PULL_LOGON(1),

    /**
     * Triggered explicitly by the user via the tray icon refresh button.
     * Used when the local vault is stale and needs to be aligned with remote
     * without a logoff/logon cycle — e.g. persistent sessions or multi-device workflows.
     */
    SYNCHRONIZE (2),

    /** Triggered at Windows logoff. Persists the session to remote. */
    PUSH_LOGOFF(3),

    /** Triggered explicitly by the user via NomadSyncCommit — local commit only,
     *  with a user-provided message. Never pushes. */
    COMMIT_MANUAL(4),

    /** Triggered periodically by the scheduler. Tolerant and deferrable. */
    AUTOSAVE(4);

    private final int priority;

    EventType(int priority) {
        this.priority = priority;
    }

    /**
     * Returns the numeric priority of this event type.
     * Lower values indicate higher urgency.
     *
     * @return priority value in range [1, 4]
     */
    public int getPriority() {
        return priority;
    }
}
