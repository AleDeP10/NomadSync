package io.aledep10.obsidiansync.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for {@link LogService}.
 *
 * <p>Each test writes to a uniquely named temporary log file to avoid
 * cross-test contamination. No teardown needed — temp files are cleaned
 * by the OS on reboot.</p>
 */
class LogServiceTest {

    // ── log() — level filtering ───────────────────────────────────────────────

    @Test
    void log_belowMinLevel_doesNotWrite() {
        String logFile = tempLogFilePath();
        LogService logService = createLogService(logFile, LogService.LogLevel.INFO);

        logService.debug("message");

        assertThat(readLogFile(logFile)).doesNotContain("message");
    }

    @Test
    void log_atMinLevel_writes() {
        String logFile = tempLogFilePath();
        LogService logService = createLogService(logFile, LogService.LogLevel.INFO);

        logService.info("expected message");

        String logs = readLogFile(logFile);
        assertThat(logs).contains("[INFO]");
        assertThat(logs).contains("expected message");
    }

    @Test
    void log_aboveMinLevel_writes() {
        String logFile = tempLogFilePath();
        LogService logService = createLogService(logFile, LogService.LogLevel.INFO);

        logService.error("critical error");

        String logs = readLogFile(logFile);
        assertThat(logs).contains("[ERROR]");
        assertThat(logs).contains("critical error");
    }

    // ── log() — format ────────────────────────────────────────────────────────

    @Test
    void log_alwaysIncludesTimestamp() {
        String logFile = tempLogFilePath();
        LogService logService = createLogService(logFile, LogService.LogLevel.DEBUG);

        logService.info("anything");

        String timestampRegex = "\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}]";
        assertThat(readLogFile(logFile)).containsPattern(timestampRegex);
    }

    // ── log() — append mode ───────────────────────────────────────────────────

    @Test
    void log_appendMode_preservesPreviousSessions() throws InterruptedException {
        String logFile = tempLogFilePath();

        createLogService(logFile, LogService.LogLevel.DEBUG).info("first session");
        Thread.sleep(1);    // ensures distinct timestamps between sessions
        createLogService(logFile, LogService.LogLevel.DEBUG).info("second session");

        String logs = readLogFile(logFile);
        assertThat(logs).contains("first session");
        assertThat(logs).contains("second session");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Generates a unique temp log file path per test invocation.
     * Uses a nanosecond timestamp to prevent cross-test file collisions.
     */
    static String tempLogFilePath() {
        return System.getProperty("java.io.tmpdir")
                + "obsidiansync-test_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS"))
                + ".log";
    }

    /**
     * Creates a {@link LogService} instance configured with the given log file path and level.
     *
     * <p>Utility method shared across test cases to avoid repeating property setup boilerplate.
     * Each test should pass a unique file path (via {@link #tempLogFilePath()}) to prevent
     * cross-test log contamination.</p>
     *
     * @param logFilePath absolute path to the log file
     * @param level       minimum log level — entries below this level will not be written
     * @return a configured {@link LogService} instance
     */
    static LogService createLogService(String logFilePath, LogService.LogLevel level) {
        Properties properties = new Properties();
        properties.setProperty("log.path",  logFilePath);
        properties.setProperty("log.level", level.toString());
        return new LogService(properties);
    }

    /**
     * Reads the content of a log file and returns it as a single string.
     *
     * <p>Returns an empty string if the file does not exist — this is expected
     * when the log level filter prevents any writes (e.g. DEBUG below INFO threshold).</p>
     *
     * @param logFilePath absolute path to the log file
     * @return file content joined by newlines, or empty string if file does not exist
     */
    static String readLogFile(String logFilePath) {
        try {
            Path path = Path.of(logFilePath);
            if (!Files.exists(path)) {
                return "";
            }
            return String.join("\n", Files.readAllLines(path));
        } catch (IOException e) {
            throw new RuntimeException("unable to read log file: " + logFilePath, e);
        }
    }
}