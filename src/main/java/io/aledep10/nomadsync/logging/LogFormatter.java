package io.aledep10.nomadsync.logging;

import java.util.List;

/**
 * Contract for formatting a log event into one or more output lines.
 *
 * <p>Returns a {@code List<String>} rather than a single string because some
 * formats produce multiple lines — {@link LineFormatter} appends one line per
 * stack trace element when a {@link Throwable} cause is present.</p>
 *
 * <p>{@link ClefFormatter} always returns a single-element list — the complete
 * CLEF JSON event is encoded in one line for Seq ingestion.</p>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link LineFormatter} — human-readable:
 *       {@code [yyyy-MM-dd HH:mm:ss.SSS] [LEVEL] [universalId] message}</li>
 *   <li>{@link ClefFormatter} — Seq CLEF JSON for structured log ingestion</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <p>Implementations must be stateless and therefore thread-safe by construction.</p>
 */
public interface LogFormatter {

    /**
     * Formats a log event into one or more output lines.
     *
     * @param level       severity level of the event
     * @param universalId UVL of the originating vault, or {@code "SYSTEM"}
     * @param message     the log message
     * @param cause       exception to format, or {@code null}
     * @return one or more formatted lines — never null, never empty
     */
    List<String> format(LogLevel level, String universalId, String message, Throwable cause);
}