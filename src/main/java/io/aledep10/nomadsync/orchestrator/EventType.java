package io.aledep10.nomadsync.orchestrator;

/**
 * Defines the types of synchronization events that can be published to the
 * {@link SyncEventQueue}.
 *
 * <h2>Priority</h2>
 * <p>Each type carries a numeric priority used by the queue to determine execution
 * order. Lower values mean higher urgency — {@link #PULL_LOGON} (1) is always
 * processed before {@link #AUTOSAVE} (5). At equal priority, events are ordered
 * by timestamp — the older event is processed first.</p>
 *
 * <h2>Vault targeting</h2>
 * <p>Each event type declares whether a target vault is mandatory via
 * {@link #isMandatoryVault()}:</p>
 * <ul>
 *   <li>{@code false} — the event may be broadcast to all vaults ({@code vaultId = null})
 *       or targeted at a specific vault ({@code vaultId} present). When no
 *       {@code --vault} flag is provided on the CLI, the event is broadcast.</li>
 *   <li>{@code true} — a specific {@code vaultId} is required. The CLI rejects the
 *       command with an error if {@code --vault} is absent. Broadcast is not
 *       meaningful for these events (e.g. {@link #COMMIT_MANUAL} carries a
 *       user-provided message that cannot be shared across multiple vaults).</li>
 * </ul>
 */
public enum EventType {

    /**
     * Triggered at OS logon — pull remote changes before the session begins.
     *
     * <p>Precondition for all other operations: ensures the local vault is up
     * to date before any edits are made. Processed first in the priority queue.</p>
     */
    PULL_LOGON(1, false),

    /**
     * Triggered explicitly by the user — full bidirectional synchronisation.
     *
     * <p>Commits local changes, pulls remote, resolves conflicts with
     * {@code -X ours}, saves the remote version for review, then pushes.
     * Used when the local vault needs to be aligned with remote without a
     * logoff/logon cycle — e.g. persistent sessions or multi-device workflows.</p>
     */
    SYNCHRONIZE(2, false),

    /**
     * Triggered at OS logoff — persist local changes to remote before the session ends.
     *
     * <p>Commits any remaining local changes and pushes to the remote repository.
     * Ensures work is not lost if the machine is shut down or the session expires.</p>
     */
    PUSH_LOGOFF(3, false),

    /**
     * Triggered explicitly by the user via {@code NomadSync commit} — local commit only.
     *
     * <p>Commits local changes with a user-provided message (from
     * {@link SyncEvent#getMessage()}). Never pushes to remote — this is a
     * deliberate checkpoint, not a synchronisation operation.</p>
     *
     * <p>{@link #isMandatoryVault()} is {@code true} — a specific vault must be
     * identified via {@code --vault} because the user-provided commit message
     * is meaningful only for a single repository.</p>
     */
    COMMIT_MANUAL(4, true),

    /**
     * Triggered periodically by {@link io.aledep10.nomadsync.scheduler.AutosaveScheduler}.
     *
     * <p>Commits local changes with an auto-generated timestamp message if the
     * working tree is dirty; no-op otherwise. Never pushes. Tolerant and deferrable —
     * processed last in the priority queue and replaced by a more recent autosave
     * event if one arrives before execution (latest-wins deduplication).</p>
     */
    AUTOSAVE(5, false);

    private final int     priority;
    private final boolean mandatoryVault;

    EventType(int priority, boolean mandatoryVault) {
        this.priority      = priority;
        this.mandatoryVault = mandatoryVault;
    }

    /**
     * Returns the numeric priority of this event type.
     *
     * <p>Lower values indicate higher urgency. Used by
     * {@link java.util.concurrent.PriorityBlockingQueue} via
     * {@link SyncEvent#compareTo(SyncEvent)} to order events in the queue.</p>
     *
     * @return priority value in range [1, 5]
     */
    public int getPriority() { return priority; }

    /**
     * Returns {@code true} if a specific vault must be identified for this event type.
     *
     * <p>When {@code true}, the CLI rejects the command with an error if
     * {@code --vault} is absent — broadcasting this event type to all vaults
     * is not meaningful. When {@code false}, the absence of {@code --vault}
     * triggers a broadcast to all registered vaults.</p>
     *
     * @return {@code true} if {@code --vault} is required, {@code false} if broadcast is allowed
     */
    public boolean isMandatoryVault() { return mandatoryVault; }
}