package io.aledep10.nomadsync.util;

/**
 * Utility class for detecting the host operating system at runtime and
 * resolving OS-specific values such as the path separator.
 *
 * <p>Detection is based on the {@code os.name} system property, which is set by
 * the JVM on startup and never changes during the lifetime of the process.
 * The result is therefore stable and may be cached by callers if needed.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * String editor    = OsUtil.isWindows() ? "notepad" : "nano";
 * String separator = OsUtil.separator(); // "\\" on Windows, "/" elsewhere
 * }</pre>
 *
 * <p>Non-instantiable — all members are {@code static}.</p>
 */
public final class OsUtil {

    private OsUtil() {}

    /**
     * Detects the host operating system.
     *
     * <p>Returns {@link Os#WINDOWS} if {@code os.name} (case-insensitive)
     * contains {@code "win"}, {@link Os#UNIX} otherwise. All Unix-like
     * systems (macOS, Linux, BSD) map to {@link Os#UNIX}.</p>
     *
     * @return the detected {@link Os} — never {@code null}
     */
    public static Os detect() {
        return System.getProperty("os.name", "")
                .toLowerCase()
                .contains("win") ? Os.WINDOWS : Os.UNIX;
    }

    /**
     * Returns {@code true} if the host OS is Windows.
     *
     * <p>Convenience wrapper over {@link #detect()} for the common boolean check.</p>
     *
     * @return {@code true} on Windows, {@code false} on any Unix-like OS
     */
    public static boolean isWindows() {
        return detect() == Os.WINDOWS;
    }

    /**
     * Returns the path separator character for the host operating system,
     * as a string ready for concatenation.
     *
     * <p>Equivalent to {@link java.io.File#separator}, exposed here for
     * consistency with the rest of {@code OsUtil} and to keep all OS-detection
     * logic centralised in one place.</p>
     *
     * @return {@code "\\"} on Windows, {@code "/"} on any Unix-like OS
     */
    public static String separator() {
        return isWindows() ? "\\" : "/";
    }
}