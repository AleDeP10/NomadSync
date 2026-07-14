package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.util.TestUtil;
import io.aledep10.nomadsync.util.TestVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for {@link LogService}.
 *
 * <p>Each test writes to the shared {@link TestVault#logFilePath()} — log entries
 * are isolated by level and content assertions rather than by separate files.
 * The test vault is cleaned in {@code @AfterEach}.</p>
 *
 * <p>{@link TestVault#rootPath()} is used as {@code configDir} throughout —
 * it is the base directory against which a relative {@code log.path} is
 * resolved; most tests supply an already-absolute {@code log.path}
 * ({@link TestVault#logFilePath()}), so {@code configDir} is inert for them.
 * The {@code configDir}-specific tests below are the ones that actually
 * exercise resolution against it.</p>
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

    @Test
    void log_belowMinLevel_doesNotWrite() {
        LogService logService = createLogService(LogLevel.INFO);

        logService.debug("should not appear");

        assertThat(readLogFile()).doesNotContain("should not appear");
    }

    @Test
    void log_atMinLevel_writes() {
        LogService logService = createLogService(LogLevel.INFO);

        logService.info("expected message");

        String content = readLogFile();
        assertThat(content).contains("[INFO]");
        assertThat(content).contains("expected message");
    }

    @Test
    void log_aboveMinLevel_writes() {
        LogService logService = createLogService(LogLevel.INFO);

        logService.error("critical error");

        String content = readLogFile();
        assertThat(content).contains("[ERROR]");
        assertThat(content).contains("critical error");
    }

    // ── log() — format ────────────────────────────────────────────────────────

    @Test
    void log_alwaysIncludesTimestamp() {
        LogService logService = createLogService(LogLevel.DEBUG);

        logService.info("anything");

        assertThat(readLogFile())
                .containsPattern("\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\]");
    }

    @Test
    void log_defaultConstructor_usesSystemRepoSlug() {
        LogService logService = createLogService(LogLevel.DEBUG);

        logService.info("check slug");

        assertThat(readLogFile()).contains("SYSTEM");
    }

    // ── log() — append mode ───────────────────────────────────────────────────

    @Test
    void log_appendMode_preservesPreviousSessions() throws InterruptedException {
        createLogService(LogLevel.DEBUG).info("first session");
        Thread.sleep(1);
        createLogService(LogLevel.DEBUG).info("second session");

        String content = readLogFile();
        assertThat(content).contains("first session");
        assertThat(content).contains("second session");
    }

    // ── constructor(Properties, Path configDir, String repoSlug) ──────────────

    @Test
    void constructor_withRepoSlug_writesRepoSlugToLog() {
        Properties properties = baseProperties(LogLevel.DEBUG);
        LogService logService = new LogService(properties, testVault.rootPath(), "Alice/portfolio");

        logService.info("vault message");

        assertThat(readLogFile()).contains("Alice/portfolio");
    }

    @Test
    void constructor_withRepoSlug_respectsMinLevel() {
        Properties properties = baseProperties(LogLevel.WARN);
        LogService logService = new LogService(properties, testVault.rootPath(), "Alice/portfolio");

        logService.info("below threshold");

        assertThat(readLogFile()).doesNotContain("below threshold");
    }

    // ── withVault() ───────────────────────────────────────────────────────────

    @Test
    void withVault_writesNewRepoSlugToLog() {
        LogService system = createLogService(LogLevel.DEBUG);
        LogService vaultScoped = system.withVault("Alice/portfolio");

        vaultScoped.info("vault-scoped message");

        assertThat(readLogFile()).contains("Alice/portfolio");
    }

    @Test
    void withVault_originalInstanceRetainsSystemSlug() {
        LogService system = createLogService(LogLevel.DEBUG);
        system.withVault("Alice/portfolio");  // discard result

        system.info("system message");

        assertThat(readLogFile()).contains("SYSTEM");
        assertThat(readLogFile()).doesNotContain("Alice/portfolio");
    }

    @Test
    void withVault_twoScopedInstances_writeDistinctSlugs() {
        LogService system  = createLogService(LogLevel.DEBUG);
        LogService vaultA  = system.withVault("Alice/portfolio");
        LogService vaultB  = system.withVault("Alice/portfolio");

        vaultA.info("from A");
        vaultB.info("from B");

        String content = readLogFile();
        assertThat(content).contains("Alice/portfolio");
        assertThat(content).contains("Alice/portfolio");
    }

    // ── buildWriters — unknown token ──────────────────────────────────────────

    @Test
    void buildWriters_unknownToken_doesNotThrow() {
        Properties properties = baseProperties(LogLevel.DEBUG);
        properties.setProperty("log.writers", "console,unknown-writer,file");

        LogService logService = new LogService(properties, testVault.rootPath());
        logService.info("still works");

        assertThat(readLogFile()).contains("still works");
    }

    @Test
    void buildWriters_fileWriterMissingPath_doesNotThrow() {
        Properties properties = new Properties();
        properties.setProperty("log.level",   LogLevel.DEBUG.name());
        properties.setProperty("log.writers", "file");
        // log.path intentionally absent

        // must not throw
        new LogService(properties, testVault.rootPath());
    }

    /**
     * Verifies that {@code log.path} present but blank is treated the same as
     * absent — the file writer is skipped, not pointed at a nonsense path
     * derived from an empty string.
     */
    @Test
    void buildWriters_blankLogPath_treatedAsAbsent_doesNotThrow() {
        Properties properties = new Properties();
        properties.setProperty("log.level",   LogLevel.DEBUG.name());
        properties.setProperty("log.writers", "file");
        properties.setProperty("log.path",    "   ");

        // must not throw
        new LogService(properties, testVault.rootPath());
    }

    @Test
    void buildWriters_seqWriterMissingUrl_doesNotThrow() {
        Properties properties = baseProperties(LogLevel.DEBUG);
        properties.setProperty("log.writers", "console,seq");
        // log.seq.url intentionally absent

        // must not throw
        new LogService(properties, testVault.rootPath());
    }

    // ── buildWriters — configDir resolution ────────────────────────────────────

    /**
     * Verifies that a relative {@code log.path} is resolved against
     * {@code configDir} — not the JVM's working directory — and that the
     * resulting file actually receives the log line.
     */
    @Test
    void buildWriters_relativeLogPath_resolvesAgainstConfigDir() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("log.level",   LogLevel.DEBUG.name());
        properties.setProperty("log.writers", "file");
        properties.setProperty("log.path",    "relative-test.log");

        Path expected = testVault.rootPath().resolve("relative-test.log");
        try {
            LogService logService = new LogService(properties, testVault.rootPath());
            logService.info("resolved against configDir");

            assertThat(Files.exists(expected)).isTrue();
            assertThat(Files.readString(expected)).contains("resolved against configDir");
        } finally {
            Files.deleteIfExists(expected);
        }
    }

    /**
     * Verifies that an already-absolute {@code log.path} is used exactly as
     * given, ignoring {@code configDir} entirely — even a deliberately
     * nonexistent {@code configDir} must not affect resolution.
     */
    @Test
    void buildWriters_absoluteLogPath_ignoresConfigDir() {
        Path bogusConfigDir = testVault.rootPath().resolve("nonexistent-config-dir");
        Properties properties = baseProperties(LogLevel.DEBUG); // log.path is already absolute

        LogService logService = new LogService(properties, bogusConfigDir);
        logService.info("absolute path wins");

        assertThat(readLogFile()).contains("absolute path wins");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LogService createLogService(LogLevel level) {
        return new LogService(baseProperties(level), testVault.rootPath());
    }

    private Properties baseProperties(LogLevel level) {
        Properties properties = new Properties();
        properties.setProperty("log.path",    testVault.logFilePath().toString());
        properties.setProperty("log.level",   level.name());
        properties.setProperty("log.writers", "file");
        return properties;
    }

    private String readLogFile() {
        try {
            if (!Files.exists(testVault.logFilePath())) return "";
            return String.join("\n", Files.readAllLines(testVault.logFilePath()));
        } catch (IOException e) {
            throw new RuntimeException("Unable to read log file: " + testVault.logFilePath(), e);
        }
    }
}