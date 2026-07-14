package io.aledep10.nomadsync.util;

import io.aledep10.nomadsync.logging.LogLevel;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Properties;

/**
 * Test infrastructure utility — {@link TestVault} factory and {@link Properties}
 * factories for all service and component tests.
 *
 * <h2>Lifecycle protocol</h2>
 * <pre>
 * {@literal @}BeforeAll  → testVault = TestUtil.getTestVault("MyTest")
 *             → logService = new LogService(TestUtil.forLogService(testVault, LogLevel.DEBUG))
 * {@literal @}BeforeEach → use testVault directly (no new getTestVault call)
 *             → properties = TestUtil.forOrchestrator(testVault)
 * {@literal @}AfterEach  → TestUtil.cleanup(testVault)
 * </pre>
 *
 * <h2>Directory layout</h2>
 * <pre>
 * {@code <java.io.tmpdir>/NomadSync/<testName>/<timestamp>/}
 *   vault/              ← vault working directory (git init here if needed)
 *   gitignore/          ← .gitignore test files
 *   logs/
 *     <testName>.log    ← log file path exposed via TestVault
 *   backup/             ← snapshot FIFO root
 *   conflict/           ← remote conflicts root
 * </pre>
 *
 * <h2>Timestamp uniqueness guarantee</h2>
 * <p>{@link #getTestVault(String)} sleeps for 1 millisecond before capturing the
 * timestamp used to name the root directory. This prevents the degenerate case where
 * two consecutive calls within the same millisecond produce identical paths — which
 * would cause one test to silently share or overwrite another test's filesystem state.
 * The sleep is cheap (sub-millisecond on modern JVMs) and invisible to test duration.</p>
 *
 * <p>This class is not meant to be instantiated — all members are {@code static}.</p>
 */
public final class TestUtil {

    private TestUtil() {}

    // ── TestVault factory ─────────────────────────────────────────────────────

    /**
     * Creates a fully isolated test environment for the given test class.
     *
     * <p>Each call sleeps 1 ms before capturing the timestamp, guaranteeing that
     * two consecutive calls always produce distinct root directories — even when
     * called within the same test run at sub-millisecond intervals.</p>
     *
     * <p>All subdirectories are created eagerly. File paths (gitignore, log file)
     * are returned as {@link Path} references without creating the files themselves —
     * tests that write those files are responsible for creating them.</p>
     *
     * @param testName test class name — used as the subdirectory identifier
     * @return a fully initialised {@link TestVault} with all paths resolved
     * @throws IOException if any directory cannot be created
     */
    public static TestVault getTestVault(String testName) throws IOException {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        Path rootPath = Path.of(TestConstants.TMP_DIR, TestConstants.APP_PREFIX,
                testName, timestamp);

        Path vaultPath     = rootPath.resolve("vault");
        Path gitignorePath = vaultPath.resolve(".gitignore");
        Path logsPath      = rootPath.resolve("logs");
        Path logFilePath   = logsPath.resolve(testName + ".log");
        Path backupPath    = rootPath.resolve("backups");
        Path conflictsPath = rootPath.resolve("remote-conflicts");

        Files.createDirectories(vaultPath);
        Files.createDirectories(logsPath);
        Files.createDirectories(backupPath);
        Files.createDirectories(conflictsPath);

        return new TestVault(timestamp, rootPath, vaultPath,
                gitignorePath, logFilePath, backupPath, conflictsPath);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Deletes the entire test root directory and all its contents.
     *
     * <p>Safe to call in {@code @AfterEach} even when the test failed before
     * creating any files — no-op if the root does not exist.</p>
     *
     * @param testVault the test environment to clean up
     * @throws IOException if deletion fails for a reason other than non-existence
     */
    public static void cleanup(TestVault testVault) throws IOException {
        Path root = testVault.rootPath();
        if (!Files.exists(root)) return;
        FileUtil.deleteRecursively(root);
    }

    // ── Properties factories ──────────────────────────────────────────────────

    /**
     * Creates {@link Properties} configured for {@link io.aledep10.nomadsync.service.LogService}.
     *
     * <p>Points the log file to {@link TestVault#logFilePath()} — isolated per test class.
     * Intended for {@code @BeforeAll} — {@code LogService} carries no mutable
     * test state and is safe to share across all tests in a class.</p>
     *
     * @param vault the test environment providing the log file path
     * @param level minimum log level; compile-time safe via {@link LogLevel}
     * @return configured {@link Properties} ready for {@code LogService} construction
     */
    public static Properties forLogService(TestVault vault, LogLevel level) {
        Properties properties = new Properties();
        properties.setProperty("log.path",  vault.logFilePath().toString());
        properties.setProperty("log.level", level.name());
        return properties;
    }

    /**
     * Creates {@link Properties} configured for {@link io.aledep10.nomadsync.service.VaultService}.
     *
     * <p>The {@code catalog.json} file is named with the {@link TestVault#timestamp()}
     * suffix — the same timestamp used to name the root directory. Since
     * {@link #getTestVault(String)} guarantees timestamp uniqueness via a 1 ms sleep,
     * this file name is unique across all test runs in the same JVM session.</p>
     *
     * <p>The file is not created by this method — {@code VaultService} creates it
     * on the first {@code save()} call.</p>
     *
     * @param vault the test environment providing the root path and timestamp
     * @return configured {@link Properties} ready for {@code VaultService} construction
     */
    public static Properties forVaultService(TestVault vault) throws IOException {
        Files.createDirectories(vault.rootPath()); // ← garantisce che la root esista
        Properties properties = new Properties();
        properties.setProperty("path.catalog",
                vault.rootPath().resolve("vaults_" + vault.timestamp() + ".json").toString());
        return properties;
    }

    /**
     * Creates {@link Properties} configured for {@link io.aledep10.nomadsync.orchestrator.SyncOrchestrator}
     * and Git service tests.
     *
     * <p>Uses the vault path from the provided {@link TestVault} — no additional
     * directory is created. The vault directory exists but is not initialised as a
     * Git repository; callers that require a real repo must run {@code git init}
     * on {@link TestVault#vaultPath()}.</p>
     *
     * <p>Does not include log properties — set those up separately via
     * {@link #forLogService(TestVault, LogLevel)} in {@code @BeforeAll}.</p>
     *
     * @param vault the test environment providing the vault path
     * @return configured {@link Properties} ready for service and orchestrator construction
     */
    public static Properties forOrchestrator(TestVault vault) {
        Properties properties = new Properties();
        properties.setProperty("vault.path",     vault.vaultPath().toString());
        properties.setProperty("git.executable", TestConstants.GIT_EXECUTABLE);
        return properties;
    }

    /**
     * Creates {@link Properties} configured for {@link io.aledep10.nomadsync.tray.SocketClient} tests.
     *
     * @param port the port the test server is listening on
     * @return configured properties with a fast retry delay suitable for tests
     */
    public static Properties forClient(int port) {
        Properties properties = new Properties();
        properties.setProperty("socket.host",       "localhost");
        properties.setProperty("socket.port",       String.valueOf(port));
        properties.setProperty("socket.retryDelay", "10");
        return properties;
    }

    /**
     * Creates {@link Properties} configured for {@link io.aledep10.nomadsync.tray.SocketServer} tests.
     *
     * <p>Allocates a free OS-assigned port by opening and immediately closing a
     * {@link ServerSocket} on port {@code 0}. There is a theoretical TOCTOU race
     * between releasing the probe port and {@code SocketServer} binding to it,
     * but this is not a problem in a single-host test environment.</p>
     *
     * @return configured properties with a free port assigned by the OS
     * @throws IOException if the probe socket cannot be opened
     */
    public static Properties forServer() throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Properties properties = new Properties();
        properties.setProperty("socket.port", String.valueOf(port));
        return properties;
    }

    /**
     * Computes the expected normalized, absolute form of a raw path literal —
     * used to assert against values returned by {@code create()}/{@code update()}
     * without hardcoding an OS-specific expectation.
     */
    public static String absolute(String raw) {
        return java.nio.file.Path.of(raw).toAbsolutePath().normalize().toString();
    }
}