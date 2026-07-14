package io.aledep10.nomadsync.logging;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Formats log events as human-readable timestamped lines.
 *
 * <p>Each event produces one primary line in the format:</p>
 * <pre>
 *   [yyyy-MM-dd HH:mm:ss.SSS] [LEVEL] [repoSlug] message
 * </pre>
 *
 * <p>If a {@link Throwable} is present, its stack trace elements are appended
 * as additional lines — one element per line — immediately after the primary
 * line. This keeps the format compatible with line-oriented log files and
 * console output without requiring special multi-line handling by writers.</p>
 *
 * <h2>Usage</h2>
 * <p>Used by {@link ConsoleLogWriter}, {@link FileLogWriter}, and
 * {@link InMemoryLogWriter}. {@link SeqHttpLogWriter} uses
 * {@link ClefFormatter} instead.</p>
 *
 * <h2>Thread safety</h2>
 * <p>{@link DateTimeFormatter} is immutable and thread-safe — the static
 * instance is safe for concurrent use by multiple log writer threads.</p>
 */
public class LineFormatter implements LogFormatter {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * Formats a log event as one or more lines.
     *
     * <p>The first element is always the primary log line. If {@code cause} is
     * non-null, each stack trace element is appended as a subsequent line.</p>
     *
     * @param level       severity level
     * @param universalId repoSlug of the originating vault, or {@code "SYSTEM"}
     * @param message     human-readable log message
     * @param cause       exception whose stack trace to append, or {@code null}
     * @return list of formatted lines; never empty
     */
    @Override
    public List<String> format(LogLevel level, String universalId,
                               String message, Throwable cause) {
        List<String> result = new ArrayList<>();
        String timestamp = LocalDateTime.now().format(FORMATTER);
        result.add("[%s] [%s] [%s] %s".formatted(timestamp, level, universalId, message));
        while (cause != null) {
            for (StackTraceElement element : cause.getStackTrace()) {
                result.add("\tat " + element);
            }
            cause  = cause.getCause();
        }
        return result;
    }
}
