package io.aledep10.nomadsync.logging;

/**
 * Contract for a log output destination.
 *
 * <p>Implementations are responsible for formatting and delivering a single
 * log event to their respective target (file, console, in-memory buffer,
 * remote server). {@link io.aledep10.nomadsync.service.LogService} holds a
 * {@code List<LogWriter>} and fans out every event to all registered writers.</p>
 *
 * <h2>Threading</h2>
 * <p>Each implementation is responsible for its own thread safety.
 * {@link FileLogWriter} uses {@code synchronized}; {@link SeqHttpLogWriter}
 * uses a {@link java.util.concurrent.BlockingQueue}.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>{@link #close()} is called at JVM shutdown. Implementations that hold
 * resources (file handles, HTTP connections, daemon threads) must release them.
 * Implementations with no resources — {@link ConsoleLogWriter},
 * {@link InMemoryLogWriter} — implement {@code close()} as a no-op.</p>
 */
public interface LogWriter {

    /**
     * Writes a single log event to this writer's target.
     *
     * @param level       severity level of the event
     * @param universalId UVL of the originating vault, or {@code "SYSTEM"} for
     *                    vault-agnostic events
     * @param message     the log message
     * @param cause       exception to include, or {@code null}
     */
    void write(LogLevel level, String universalId, String message, Throwable cause);

    /**
     * Releases any resources held by this writer.
     *
     * <p>Called once at JVM shutdown. After this method returns, no further
     * {@link #write} calls will be made on this instance.</p>
     */
    void close();
}