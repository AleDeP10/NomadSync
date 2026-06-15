package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.util.TestUtil;
import io.aledep10.nomadsync.util.TestVault;
import org.junit.jupiter.api.AfterEach;
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
     */
    @Test
    void log_belowMinLevel_doesNotWrite() {
        LogService logService = createLogService(LogLevel.INFO);

        logService.debug("should not appear");

        assertThat(readLogFile()).doesNotContain("should not appear");
    }

    /**
     * Verifies that an event at exactly the minimum level is written with level tag and message.
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
     * Verifies that an event above the minimum level is written.
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

    /**
     * Verifies that every log line includes the repoSlug field.
     * Default constructor uses "SYSTEM" as repoSlug.
     */
    @Test
    void log_defaultConstructor_usesSystemRepoSlug() {
        LogService logService = createLogService(LogLevel.DEBUG);

        logService.info("check slug");

        assertThat(readLogFile()).contains("SYSTEM");
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

    // ── constructor(Properties, String repoSlug) ──────────────────────────────

    /**
     * Verifies that the vault-scoped constructor writes the provided repoSlug
     * to every log line.
     */
    @Test
    void constructor_withRepoSlug_writesRepoSlugToLog() {
        Properties properties = baseProperties(LogLevel.DEBUG);
        LogService logService = new LogService(properties, "AleDeP10/public-vault");

        logService.info("vault message");

        assertThat(readLogFile()).contains("AleDeP10/public-vault");
    }

    /**
     * Verifies that the vault-scoped constructor still filters by level.
     */
    @Test
    void constructor_withRepoSlug_respectsMinLevel() {
        Properties properties = baseProperties(LogLevel.WARN);
        LogService logService = new LogService(properties, "AleDeP10/public-vault");

        logService.info("below threshold");

        assertThat(readLogFile()).doesNotContain("below threshold");
    }

    // ── withVault() ───────────────────────────────────────────────────────────

    /**
     * Verifies that {@code withVault()} returns a new instance scoped to the
     * given repoSlug — the slug appears in every subsequent log line.
     */
    @Test
    void withVault_writesNewRepoSlugToLog() {
        LogService system = createLogService(LogLevel.DEBUG);
        LogService vaultScoped = system.withVault("AleDeP10/private-vault");

        vaultScoped.info("vault-scoped message");

        assertThat(readLogFile()).contains("AleDeP10/private-vault");
    }

    /**
     * Verifies that the original {@code LogService} instance retains its own
     * repoSlug after {@code withVault()} is called — instances are independent.
     */
    @Test
    void withVault_originalInstanceRetainsSystemSlug() {
        LogService system = createLogService(LogLevel.DEBUG);
        system.withVault("AleDeP10/private-vault");  // discard result

        system.info("system message");

        assertThat(readLogFile()).contains("SYSTEM");
        assertThat(readLogFile()).doesNotContain("AleDeP10/private-vault");
    }

    /**
     * Verifies that two vault-scoped instances derived from the same system
     * instance are independent — each writes its own slug.
     */
    @Test
    void withVault_twoScopedInstances_writeDistinctSlugs() {
        LogService system  = createLogService(LogLevel.DEBUG);
        LogService vaultA  = system.withVault("AleDeP10/public-vault");
        LogService vaultB  = system.withVault("AleDeP10/private-vault");

        vaultA.info("from A");
        vaultB.info("from B");

        String content = readLogFile();
        assertThat(content).contains("AleDeP10/public-vault");
        assertThat(content).contains("AleDeP10/private-vault");
    }

    // ── buildWriters — unknown token ──────────────────────────────────────────

    /**
     * Verifies that an unknown writer token in {@code log.writers} does not throw —
     * the service starts with the remaining valid writers.
     */
    @Test
    void buildWriters_unknownToken_doesNotThrow() {
        Properties properties = baseProperties(LogLevel.DEBUG);
        properties.setProperty("log.writers", "console,unknown-writer,file");

        LogService logService = new LogService(properties);
        logService.info("still works");

        assertThat(readLogFile()).contains("still works");
    }

    /**
     * Verifies that requesting the {@code file} writer without {@code log.path}
     * does not throw — the writer is skipped and console remains active.
     */
    @Test
    void buildWriters_fileWriterMissingPath_doesNotThrow() {
        Properties properties = new Properties();
        properties.setProperty("log.level",   LogLevel.DEBUG.name());
        properties.setProperty("log.writers", "file");
        // log.path intentionally absent

        // must not throw
        new LogService(properties);
    }

    /**
     * Verifies that requesting the {@code seq} writer without {@code log.seq.url}
     * does not throw — the writer is skipped.
     */
    @Test
    void buildWriters_seqWriterMissingUrl_doesNotThrow() {
        Properties properties = baseProperties(LogLevel.DEBUG);
        properties.setProperty("log.writers", "console,seq");
        // log.seq.url intentionally absent

        // must not throw
        new LogService(properties);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LogService createLogService(LogLevel level) {
        return new LogService(baseProperties(level));
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