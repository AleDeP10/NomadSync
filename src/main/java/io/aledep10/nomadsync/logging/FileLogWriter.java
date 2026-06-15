package io.aledep10.nomadsync.logging;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

public class FileLogWriter implements LogWriter {

    private static final LogFormatter formatter = new LineFormatter();
    private final File logFile;

    public FileLogWriter(Path logFile) {
        this.logFile = logFile.toFile();
    }

    public synchronized void write(LogLevel level, String universalId,
                                   String message, Throwable cause) {
        List<String> lines = formatter.format(level, universalId, message, cause);
        try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
            // [NOTA] pirletta, println è un metodo di PrintWriter, non BufferedWriter!
            lines.forEach(writer::println);   // ← append mode
        } catch (IOException e) {
            System.err.println("Unable to write log file: " + logFile.getAbsolutePath());
        }
    }

    public void close() {
        // no-op — FileWriter apre e chiude per ogni write
    }
}