package io.aledep10.nomadsync.tray;

/**
 * Represents a synchronisation event transmitted over the local TCP socket.
 *
 * <p>Serialised as JSON via {@link #toString()} for transmission.
 * Carries retry state (count and delay) for exponential backoff management
 * inside {@link SocketClient}.</p>
 *
 * <p>Delay progression: {@link #INITIAL_RETRY_DELAY_MS} → ×2 per retry
 * (30s → 60s → 120s).</p>
 */
public class SocketMessage {

    public static final long INITIAL_RETRY_DELAY_MS = 30_000L;

    private final String event;
    private final String vaultId;
    private final long timestamp;
    private int retryCount;
    private long retryDelay;

    /**
     * Constructs a new SocketMessage with the current timestamp and zeroed retry state.
     *
     * <p>The {@code retryDelay} parameter sets the initial backoff delay in milliseconds.
     * Each call to {@link #incrementRetry()} doubles this value progressively.</p>
     *
     * @param event      the event type name (e.g. {@code "PULL_LOGON"})
     * @param vaultId    the target vault identifier
     * @param retryDelay initial retry delay in milliseconds (e.g. {@code 30_000L} for 30s)
     */
    public SocketMessage(String event, String vaultId, long retryDelay) {
        this.event      = event;
        this.vaultId    = vaultId;
        this.timestamp  = System.currentTimeMillis();
        this.retryCount = 0;
        this.retryDelay = retryDelay;
    }

    /**
     * Increments the retry counter and doubles the retry delay (exponential backoff).
     * Delay progression: 30s → 60s → 120s.
     */
    public void incrementRetry() {
        this.retryCount++;
        this.retryDelay *= 2;
    }

    public String getEvent()        { return event; }
    public String getVaultId()      { return vaultId; }
    public long   getTimestamp()    { return timestamp; }
    public int    getRetryCount()   { return retryCount; }
    public long   getRetryDelay()   { return retryDelay; }

    /**
     * Package-private setters for testing purposes only.
     * Allow controlled manipulation of retry state in test scenarios.
     */
    void setRetryCount(int retryCount)   { this.retryCount = retryCount; }
    void setRetryDelay(long retryDelay)  { this.retryDelay = retryDelay; }

    /**
     * Serialises the message as a JSON string for socket transmission.
     *
     * @return JSON representation of this message
     */
    @Override
    public String toString() {
        return "{\"event\":\"%s\",\"vaultId\":\"%s\",\"timestamp\":%d,\"retryCount\":%d,\"retryDelay\":%d}"
                .formatted(event, vaultId, timestamp, retryCount, retryDelay);
    }
}
