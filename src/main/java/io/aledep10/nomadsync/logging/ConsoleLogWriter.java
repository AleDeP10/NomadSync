package io.aledep10.nomadsync.logging;

import java.util.List;

/**
 * Writes log events to the console via {@link System#out} and {@link System#err}.
 *
 * <p>Events at {@link LogLevel#WARN} and {@link LogLevel#ERROR} are written to
 * {@code stderr}; all others go to {@code stdout}. This allows the caller's shell
 * to redirect error output independently of informational output.</p>
 *
 * <h2>Formatting</h2>
 * <p>Uses {@link LineFormatter} to produce human-readable timestamped lines —
 * e.g. {@code [2026-06-16 19:00:00.000] [INFO] [AleDeP10/public-vault] message}.</p>
 *
 * <h2>Exception output</h2>
 * <p>If a {@link Throwable} is present, its stack trace is printed immediately
 * after the formatted log lines to the same stream as the event.</p>
 */
public class ConsoleLogWriter implements LogWriter {

    private static final LogFormatter FORMATTER = new LineFormatter();

    /**
     * Writes a log event to {@code stdout} or {@code stderr} depending on severity.
     *
     * @param level       severity level
     * @param universalId repoSlug of the originating vault, or {@code "SYSTEM"}
     * @param message     human-readable log message
     * @param cause       exception to print, or {@code null}
     */
    @Override
    public void write(LogLevel level, String universalId,
                      String message, Throwable cause) {
        List<String> lines = FORMATTER.format(level, universalId, message, cause);
        switch (level) {
            case WARN, ERROR -> {
                lines.forEach(System.err::println);
                if (cause != null) cause.printStackTrace(System.err);
            }
            default -> {
                lines.forEach(System.out::println);
                if (cause != null) cause.printStackTrace(System.out);
            }
        }
    }

    /**
     * No-op — console streams are managed by the JVM and must not be closed here.
     */
    @Override
    public void close() {}
}
