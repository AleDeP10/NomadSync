package io.aledep10.nomadsync.logging;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates log events in memory for programmatic inspection.
 *
 * <p>Not a user-configurable writer (it is not listed in
 * {@link io.aledep10.nomadsync.config.NomadProperties.Log#WRITERS}) — instantiated
 * directly by code that needs to inspect log output at runtime, such as tray UI
 * components that buffer recent log lines for display.</p>
 *
 * <h2>Thread safety</h2>
 * <p>{@link #write}, {@link #getLines()}, and {@link #clear()} are not
 * synchronised — this writer is intended for single-threaded test scenarios or
 * UI components that access it from the Event Dispatch Thread. If concurrent
 * access is required, the caller is responsible for external synchronisation.</p>
 *
 * <h2>Formatting</h2>
 * <p>Uses {@link LineFormatter} to produce the same human-readable format as
 * {@link ConsoleLogWriter} and {@link FileLogWriter}.</p>
 */
public class InMemoryLogWriter implements LogWriter {

    private static final LogFormatter FORMATTER = new LineFormatter();

    private final List<String> lines = new ArrayList<>();

    /**
     * Appends the formatted log event to the in-memory line buffer.
     *
     * @param level       severity level
     * @param universalId repoSlug of the originating vault, or {@code "SYSTEM"}
     * @param message     human-readable log message
     * @param cause       exception to format, or {@code null}
     */
    @Override
    public void write(LogLevel level, String universalId,
                      String message, Throwable cause) {
        lines.addAll(FORMATTER.format(level, universalId, message, cause));
    }

    /**
     * No-op — no resources to release.
     */
    @Override
    public void close() {}

    /**
     * Returns a defensive copy of all accumulated log lines.
     *
     * <p>The returned list is independent of the internal buffer — modifications
     * do not affect the writer state.</p>
     *
     * @return copy of all formatted log lines in insertion order
     */
    public List<String> getLines() {
        return new ArrayList<>(lines);
    }

    /**
     * Clears all accumulated log lines.
     *
     * <p>Typically called between test cases to reset state.</p>
     */
    public void clear() {
        lines.clear();
    }
}
