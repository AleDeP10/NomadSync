package io.aledep10.nomadsync.util;

/**
 * Enumerates the operating system families recognised by NomadSync.
 *
 * <p>Used by {@link OsUtil} to provide platform-specific defaults —
 * currently the fallback editor for {@code NomadSync commit}
 * ({@code notepad} on Windows, {@code nano} on Unix).</p>
 *
 * <p>All non-Windows platforms (macOS, Linux) are grouped under {@link #UNIX} —
 * the distinction between them is not relevant for the features that depend
 * on this enum.</p>
 */
public enum Os {

    /** Microsoft Windows — identified by {@code os.name} containing {@code "win"}. */
    WINDOWS,

    /** Any Unix-like OS (macOS, Linux, BSD, etc.). */
    UNIX
}