package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.scheduler.AutosaveScheduler;
import io.aledep10.nomadsync.logging.LogLevel;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * Provides levelled, append-only logging to both console and file.
 *
 * <p>Log entries follow the format: {@code [TIMESTAMP] [LEVEL] message}</p>
 *
 * <p>All write operations are {@code synchronized} to ensure thread safety
 * when called concurrently from {@link AutosaveScheduler} and the main thread.</p>
 */
public class LogService {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final File logFile;
    private final LogLevel minLevel;

    /**
     * Constructs a LogService from the provided configuration.
     *
     * @param properties application properties containing {@code log.path}
     *                   and {@code log.level}
     */
    public LogService(Properties properties) {
        this.logFile = new File(properties.getProperty("log.path"));
        this.minLevel = LogLevel.valueOf(properties.getProperty("log.level"));
    }

    public void debug(String message) { log(LogLevel.DEBUG, message); }
    public void debug(String message, Throwable cause) { log(LogLevel.DEBUG, message, cause); }
    public void info(String message)  { log(LogLevel.INFO,  message); }
    public void info(String message, Throwable cause) { log(LogLevel.INFO, message, cause); }
    public void warn(String message)  { log(LogLevel.WARN,  message); }
    public void warn(String message, Throwable cause) { log(LogLevel.WARN, message, cause); }
    public void error(String message) { log(LogLevel.ERROR, message); }
    public void error(String message, Throwable cause) { log(LogLevel.ERROR, message, cause); }

    /**
     * Core logging method. Filters by level, formats the entry,
     * writes to file and console.
     *
     * <p>Synchronized to prevent interleaved writes from concurrent threads.</p>
     *
     * @param level   severity level of this entry
     * @param message log message
     */
    private synchronized void log(LogLevel level, String message) {
        log(level, message, null);
    }
    private synchronized void log(LogLevel level, String message, Throwable cause) {
        if (level.compareTo(minLevel) < 0) {
            return;
        }

        // Build log line: [2025-01-15 10:23:45.123] [INFO] message
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String line = "[" + timestamp + "] [" + level + "] " + message;

        // Write to file in append mode
        try (BufferedWriter writer = new BufferedWriter(
                new FileWriter(logFile, true))) {      // true = append
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            System.err.println("I/O error while logging: " + e.getMessage());
        }

        // Write to console
        switch (level) {
            case WARN, ERROR -> {
                System.err.println(line);
                if (cause != null) {
                    cause.printStackTrace(System.err);
                }
            }
            default -> {
                System.out.println(line);
                if (cause != null) {
                    cause.printStackTrace(System.out);
                }
            }
        }

    }
}
