package io.aledep10.nomadsync.logging;

import java.util.List;

public class ConsoleLogWriter implements LogWriter {

    private static final LogFormatter formatter = new LineFormatter();

    public void write(LogLevel level, String universalId,
                      String message, Throwable cause) {
        List<String> lines = formatter.format(level, universalId, message, cause);
        switch(level) {
            case WARN, ERROR -> {
                lines.forEach(System.err::println);
                if (cause != null) {
                    cause.printStackTrace(System.err);
                }
                break;
            }
            default -> {
                lines.forEach(System.out::println);
                if (cause != null) {
                    cause.printStackTrace(System.out);
                }
            }
        }
    }

    public void close() {
        // no-op
    }
}