package io.aledep10.nomadsync.logging;

import java.util.ArrayList;
import java.util.List;

public class InMemoryLogWriter implements LogWriter {

    private static final LogFormatter formatter = new LineFormatter();
    private final List<String> lines = new ArrayList<>();

    public void write(LogLevel level, String universalId,
                      String message, Throwable cause) {
        lines.addAll(formatter.format(level, universalId, message, cause));
    }

    public void close() {
        // no-op
    }

    public List<String> getLines() {
        return new ArrayList<>(lines);      // ← copia difensiva
    }

    public void clear() {
        lines.clear();
    }
}