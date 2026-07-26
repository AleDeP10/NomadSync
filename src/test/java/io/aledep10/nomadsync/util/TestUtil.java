package io.aledep10.nomadsync.util;

import io.aledep10.nomadsync.Main;
import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.service.VaultService;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
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
 * <p>Every NomadSync test-generated temporary directory — whether from
 * {@link #getTestVault(String)} or the lighter-weight {@link #testTempDir}
 * (used for ad-hoc directories a test needs beyond its main
 * {@link TestVault}, e.g. multiple vault-content folders in the same test
 * method) — lives under the same root, {@code <java.io.tmpdir>/NomadSync_tests/},
 * bucketed by test class name. This keeps filesystem-level troubleshooting
 * tractable: everything NomadSync's test suite ever creates is discoverable
 * from one place, never scattered flat alongside unrelated software's temp
 * output.</p>
 * <pre>
 * {@code <java.io.tmpdir>/NomadSync_tests/}
 *   {@code <testName>/<timestamp>/}     ← from getTestVault(testName)
 *     vault/              ← vault working directory (git init here if needed)
 *     gitignore/          ← .gitignore test files
 *     logs/
 *       {@code <testName>.log}    ← log file path exposed via TestVault
 *     backups/            ← snapshot FIFO root
 *     remote-conflicts/   ← remote conflicts root
 *   {@code <testClassName>/<prefix>_<random>/}  ← from testTempDir(testClassName, prefix)
 * </pre>
 *
 * <h2>Timestamp uniqueness guarantee</h2>
 * <p>{@link #getTestVault(String)} sleeps for 1 millisecond before capturing the
 * timestamp used to name the root directory. This prevents the degenerate case where
 * two consecutive calls within the same millisecond produce identical paths — which
 * would cause one test to silently share or overwrite another test's filesystem state.
 * The sleep is cheap (sub-millisecond on modern JVMs) and invisible to test duration.</p>
 *
 * <p>{@link #testTempDir} uses a different, equally valid uniqueness mechanism —
 * {@link Files#createTempDirectory}, whose random suffix is generated and checked
 * atomically by the filesystem itself, suited to the bursty,
 * multiple-calls-per-test-method pattern that ad-hoc directories need,
 * as opposed to {@code getTestVault}'s single call per test class.</p>
 *
 * <p>This class is not meant to be instantiated — all members are {@code static}.</p>
 */
public final class TestUtil {

    private TestUtil() {}

    /**
     * Root folder name for ALL NomadSync test-generated temporary directories —
     * shared by {@link #getTestVault} and {@link #testTempDir}, so every test
     * class's output lands under one discoverable tree instead of two separate,
     * inconsistently-named roots.
     */
    private static final String TEST_TEMP_ROOT_NAME = "NomadSync_tests";

    /**
     * Creates a fresh, uniquely-named temp directory for ad-hoc test needs
     * beyond a test class's main {@link TestVault} — e.g. multiple independent
     * vault-content folders within the same test method (nesting/marker tests).
     *
     * <p>Nests under the shared {@link #TEST_TEMP_ROOT_NAME} root, bucketed by
     * {@code testClassName}, so ad-hoc directories land in the same
     * discoverable tree as every {@link #getTestVault(String)}-created root —
     * not a separate, inconsistently-named location.</p>
     *
     * <p>Uniqueness is guaranteed atomically by {@link Files#createTempDirectory},
     * safe even under many rapid calls within the same test method (unlike a
     * hand-rolled timestamp, which would need its own collision-avoidance logic
     * for that usage pattern).</p>
     *
     * @param testClassName the test class requesting the directory — used as
     *                      the bucket subdirectory, e.g. {@code "VaultService"}
     * @param prefix        short, descriptive prefix for the leaf directory
     *                      name, e.g. {@code "claim-ancestor"}
     * @return the newly created, guaranteed-unique directory
     * @throws IOException if the directory cannot be created
     */
    public static Path testTempDir(String testClassName, String prefix) throws IOException {
        Path base = Path.of(TestConstants.TMP_DIR, TEST_TEMP_ROOT_NAME, testClassName);
        Files.createDirectories(base);
        return Files.createTempDirectory(base, prefix + "_");
    }

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
        Path rootPath = Path.of(TestConstants.TMP_DIR, TEST_TEMP_ROOT_NAME,
                testName, timestamp);

        Path vaultPath     = rootPath.resolve("vault");
        Path gitignorePath = vaultPath.resolve(".gitignore");
        Path logsPath      = rootPath.resolve("logs");
        Path logFilePath   = logsPath.resolve(testName + ".log");
        Path backupPath    = rootPath.resolve(VaultService.BACKUPS_FOLDER_NAME);
        Path conflictsPath = rootPath.resolve(VaultService.CONFLICTS_FOLDER_NAME);

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

    /**
     * Builds an OS-native path string from the given segments, prefixing each
     * with {@link OsUtil#separator()}.
     *
     * @param parts path segments without leading or trailing separators
     * @return an OS-native path string
     */
    public static String createPath(String... parts) {
        final StringBuilder buf = new StringBuilder();
        Arrays.asList(parts).forEach(part -> buf.append(OsUtil.separator()).append(part));
        return buf.toString();
    }

    /**
     * Reflectively invokes a private static method on the given class
     * (e.g. {@link Main}, {@link io.aledep10.nomadsync.cli.VaultCli}).
     *
     * @param clazz          the class declaring the private static method
     * @param methodName     name of the private static method
     * @param parameterTypes parameter types for method lookup
     * @param args           arguments to pass to the method
     * @return the method's return value, or {@code null} for {@code void} methods
     * @throws RuntimeException wrapping any reflection or invocation exception —
     *         when the invoked method throws a checked exception, the real cause
     *         is nested two levels down: {@code RuntimeException.getCause()} is
     *         an {@link InvocationTargetException}, whose own {@code getCause()}
     *         is the exception actually thrown by the production method.
     */
    public static Object invoke(Class<?> clazz, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}