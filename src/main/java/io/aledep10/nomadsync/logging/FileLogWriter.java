package io.aledep10.nomadsync.logging;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Appends log events to a file, one formatted line per event.
 *
 * <h2>File handling</h2>
 * <p>The log file is opened in append mode on every {@link #write} call and
 * closed immediately after — no file handle is held between calls. This avoids
 * file-locking issues on Windows and ensures all writes are flushed without
 * requiring an explicit flush call.</p>
 *
 * <p>The parent directory is created automatically if absent — this allows
 * {@code log.path=logs/nomadsync.log} to work without requiring the {@code logs/}
 * directory to be created manually.</p>
 *
 * <h2>Thread safety</h2>
 * <p>{@link #write} is {@code synchronized} — multiple threads (worker, broadcaster,
 * scheduler) share the same writer instance via {@link LogService#withVault(String)},
 * and file appends must be serialised to avoid interleaved output.</p>
 *
 * <h2>Formatting</h2>
 * <p>Uses {@link LineFormatter} to produce human-readable timestamped lines.</p>
 */
public class FileLogWriter implements LogWriter {

    private static final LogFormatter FORMATTER = new LineFormatter();

    private final Path logFile;

    /**
     * Constructs a writer targeting the given log file path.
     *
     * <p>The file and its parent directories are created automatically on the
     * first {@link #write} call if they do not already exist.</p>
     *
     * @param logFile absolute or relative path to the log file
     */
    public FileLogWriter(Path logFile) {
        this.logFile = logFile;
    }

    /**
     * Formats and appends a log event to the log file.
     *
     * <p>The parent directory is created if absent. Opens the file in append
     * mode, writes all formatted lines, and closes immediately — no handle
     * is retained between calls.</p>
     *
     * <p>If the file cannot be written, a warning is printed to {@code stderr}
     * and the method returns silently — log failures must never propagate to
     * the caller.</p>
     *
     * @param level       severity level
     * @param universalId repoSlug of the originating vault, or {@code "SYSTEM"}
     * @param message     human-readable log message
     * @param cause       exception whose stack trace to append, or {@code null}
     */
    @Override
    public synchronized void write(LogLevel level, String universalId,
                                   String message, Throwable cause) {
        List<String> lines = FORMATTER.format(level, universalId, message, cause);
        try {
            Files.createDirectories(logFile.getParent());
            try (PrintWriter writer = new PrintWriter(
                    new FileWriter(logFile.toFile(), true))) {
                lines.forEach(writer::println);
            }
        } catch (IOException e) {
            System.err.println("[FileLogWriter] Unable to write log file: "
                    + logFile.toAbsolutePath() + " — " + e.getMessage());
        }
    }

    /**
     * No-op — the file handle is opened and closed on every write;
     * there is nothing to release here.
     */
    @Override
    public void close() {}
}
