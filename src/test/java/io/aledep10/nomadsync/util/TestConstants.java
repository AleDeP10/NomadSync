package io.aledep10.nomadsync.util;

/**
 * Shared constants for test infrastructure.
 *
 * <p>Centralises system-level values used across test classes to avoid scattering
 * literals and ensure a single point of change. All constants are resolved at
 * class-load time — safe for use in both {@code @BeforeAll} and {@code @BeforeEach}
 * contexts.</p>
 *
 * <p>This class is not meant to be instantiated — all members are {@code static}.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Path logDir = Path.of(TestConstants.TMP_DIR, TestConstants.APP_PREFIX, "MyTest", "logs");
 * CommandUtil.runCommand(dir, List.of(TestConstants.GIT_EXECUTABLE, "init"));
 * CommandUtil.runCommand(dir, List.of(TestConstants.GIT_EXECUTABLE, "config",
 *     "user.name", TestConstants.GIT_NAME));
 * }</pre>
 */
public final class TestConstants {

    /**
     * Root temporary directory provided by the OS ({@code java.io.tmpdir}).
     * All test artifacts are created under this directory to ensure automatic
     * cleanup on OS reboot and no interference with project source files.
     */
    public static final String TMP_DIR = System.getProperty("java.io.tmpdir");

    /**
     * Application-level prefix for all test directories under {@link #TMP_DIR}.
     * Creates a dedicated namespace that avoids collisions with other tools
     * writing to the temp directory.
     *
     * <p>Full path structure: {@code <TMP_DIR>/NomadSync/<testName>/<artifact>/}</p>
     */
    public static final String APP_PREFIX = "NomadSync";

    /**
     * Git executable name — {@code git} resolves via PATH on both Windows and macOS.
     * Override in config if the Git binary is not on PATH (e.g. {@code C:/Program Files/Git/bin/git.exe}).
     */
    public static final String GIT_EXECUTABLE = "git";

    /**
     * Git {@code user.name} used when initialising temporary test repositories.
     *
     * <p>Git requires a configured author identity to create commits. These values
     * are test-only and do not correspond to any real GitHub account.</p>
     */
    public static final String GIT_NAME = "Test User";

    /**
     * Git {@code user.email} used when initialising temporary test repositories.
     *
     * @see #GIT_NAME
     */
    public static final String GIT_EMAIL = "test@test.com";

    private TestConstants() {
        // utility class — no instances
    }
}
