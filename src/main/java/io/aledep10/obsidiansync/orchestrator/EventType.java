package io.aledep10.obsidiansync.orchestrator;

/**
 * Defines the types of synchronization events that can be published to the {@link SyncEventQueue}.
 *
 * <p>Each type carries a numeric priority used by the queue to determine execution order.
 * Lower values mean higher urgency — {@code PULL_LOGON} (1) is always processed before
 * {@code AUTOSAVE} (4).</p>
 */
public enum EventType {

    /** Triggered at Windows logon. Precondition for all other operations. */
    PULL_LOGON(1),

    /** Triggered explicitly by the user (e.g. tray icon). Reflects deliberate intent. */
    PUSH_MANUAL(2),

    /** Triggered at Windows logoff. Persists the session to remote. */
    PUSH_LOGOFF(3),

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