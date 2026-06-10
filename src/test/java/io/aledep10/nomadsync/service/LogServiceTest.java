package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.util.TestUtil;
import io.aledep10.nomadsync.util.TestVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for {@link LogService}.
 *
 * <p>Each test writes to the shared {@link TestVault#logFilePath()} — log entries
 * are isolated by level and content assertions rather than by separate files.
 * The test vault is cleaned in {@code @AfterEach}.</p>
 */
class LogServiceTest {

    TestVault testVault;

    @BeforeEach
    void setUp() throws IOException {
        testVault = TestUtil.getTestVault("LogServiceTest");
        Files.createDirectories(testVault.logFilePath().getParent());
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(testVault.logFilePath());
    }

    // ── log() — level filtering ───────────────────────────────────────────────

    /**
     * Verifies that events below the configured minimum level are not written to the file.
     * A {@code DEBUG} message must not appear when the threshold is {@code INFO}.
     */
    @Test
    void log_belowMinLevel_doesNotWrite() {
        LogService logService = createLogService(LogLevel.INFO);

        logService.debug("should not appear");

        assertThat(readLogFile()).doesNotContain("should not appear");
    }

    /**
     * Verifies that an event at exactly the minimum level is written to the file,
     * with the level tag and message both present.
     */
    @Test
    void log_atMinLevel_writes() {
        LogService logService = createLogService(LogLevel.INFO);

        logService.info("expected message");

        String content = readLogFile();
        assertThat(content).contains("[INFO]");
        assertThat(content).contains("expected message");
    }

    /**
     * Verifies that an event above the minimum level is written — an {@code ERROR}
     * message passes through a service configured at {@code INFO}.
     */
    @Test
    void log_aboveMinLevel_writes() {
        LogService logService = createLogService(LogLevel.INFO);

        logService.error("critical error");

        String content = readLogFile();
        assertThat(content).contains("[ERROR]");
        assertThat(content).contains("critical error");
    }

    // ── log() — format ────────────────────────────────────────────────────────

    /**
     * Verifies that every log line includes a timestamp in the format
     * {@code [yyyy-MM-dd HH:mm:ss.SSS]}.
     */
    @Test
    void log_alwaysIncludesTimestamp() {
        LogService logService = createLogService(LogLevel.DEBUG);

        logService.info("anything");

        assertThat(readLogFile())
                .containsPattern("\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\]");
    }

    // ── log() — append mode ───────────────────────────────────────────────────

    /**
     * Verifies that two {@link LogService} instances writing to the same file
     * both append — neither truncates the existing content.
     */
    @Test
    void log_appendMode_preservesPreviousSessions() throws InterruptedException {
        createLogService(LogLevel.DEBUG).info("first session");
        Thread.sleep(1);
        createLogService(LogLevel.DEBUG).info("second session");

        String content = readLogFile();
        assertThat(content).contains("first session");
        assertThat(content).contains("second session");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a {@link LogService} writing to the shared {@link TestVault#logFilePath()}.
     *
     * @param level minimum log level
     * @return a configured {@link LogService} instance
     */
    private LogService createLogService(LogLevel level) {
        Properties properties = new Properties();
        properties.setProperty("log.path",  testVault.logFilePath().toString());
        properties.setProperty("log.level", level.name());
        return new LogService(properties);
    }

    /**
     * Reads the content of the shared log file as a single string.
     * Returns an empty string if the file does not exist.
     *
     * @return log file content, or empty string if absent
     */
    private String readLogFile() {
        try {
            if (!Files.exists(testVault.logFilePath())) return "";
            return String.join("\n", Files.readAllLines(testVault.logFilePath()));
        } catch (IOException e) {
            throw new RuntimeException("Unable to read log file: " + testVault.logFilePath(), e);
        }
    }
}