package io.aledep10.nomadsync.util;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Centralises all {@link DateTimeFormatter} instances used across NomadSync.
 *
 * <p>Each constant is a named, pre-compiled formatter — no {@code DateTimeFormatter.ofPattern(...)}
 * calls are scattered across the codebase. All formatters are thread-safe by construction
 * ({@link DateTimeFormatter} is immutable).</p>
 *
 * <h2>Formatter catalogue</h2>
 * <table border="1">
 *   <caption>Available formatters</caption>
 *   <tr><th>Constant</th><th>Pattern</th><th>Example</th><th>Used by</th></tr>
 *   <tr><td>{@link #LOG}</td><td>{@code yyyy-MM-dd HH:mm:ss.SSS}</td>
 *       <td>{@code 2026-05-20 12:30:00.123}</td>
 *       <td>{@link io.aledep10.nomadsync.logging.LineFormatter}</td></tr>
 *   <tr><td>{@link #SNAPSHOT}</td><td>{@code yyyy-MM-dd_HH-mm}</td>
 *       <td>{@code 2026-05-20_12-30}</td>
 *       <td>{@link io.aledep10.nomadsync.service.VaultService} snapshot FIFO</td></tr>
 *   <tr><td>{@link #COMPACT}</td><td>{@code yyyyMMdd_HHmmss_SSS}</td>
 *       <td>{@code 20260520_123000_123}</td>
 *       <td>io.aledep10.nomadsync.util.TestUtil test directory naming</td></tr>
 *   <tr><td>{@link #DATE_ONLY}</td><td>{@code yyyy-MM-dd}</td>
 *       <td>{@code 2026-05-20}</td>
 *       <td>General date display, future UI labels</td></tr>
 *   <tr><td>{@link #ISO_INSTANT}</td><td>{@code yyyy-MM-dd'T'HH:mm:ss.SSSXXX}</td>
 *       <td>{@code 2026-05-20T12:30:00.123+02:00}</td>
 *       <td>{@link io.aledep10.nomadsync.logging.ClefFormatter} CLEF {@code @t} field</td></tr>
 * </table>
 *
 * <h2>Convenience factory methods</h2>
 * <p>{@link #nowLog()}, {@link #nowSnapshot()}, {@link #nowCompact()} return the current
 * timestamp as a formatted string — single-call replacements for the
 * {@code LocalDateTime.now().format(...)} boilerplate.</p>
 *
 * <p>This class is not meant to be instantiated — all members are {@code static}.</p>
 */
public final class DateFormats {

    private DateFormats() {}

    // ── Formatters ────────────────────────────────────────────────────────────

    /**
     * Human-readable log timestamp: {@code yyyy-MM-dd HH:mm:ss.SSS}.
     *
     * <p>Used by {@link io.aledep10.nomadsync.logging.LineFormatter} to prefix
     * every log line with a millisecond-precision timestamp.</p>
     */
    public static final DateTimeFormatter LOG =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * Snapshot directory suffix: {@code yyyy-MM-dd_HH-mm}.
     *
     * <p>Used by {@link io.aledep10.nomadsync.service.VaultService} to name
     * FIFO backup directories — e.g. {@code vault_2026-05-20_12-30}.
     * Minute-precision is sufficient; two snapshots within the same minute
     * are prevented by the FIFO max-3 eviction policy.</p>
     */
    public static final DateTimeFormatter SNAPSHOT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");

    /**
     * Compact millisecond-precision timestamp: {@code yyyyMMdd_HHmmss_SSS}.
     *
     * <p>Used by io.aledep10.nomadsync.util.TestUtil to generate unique
     * test directory names. The 1 ms sleep in {@code getTestVault()} combined
     * with millisecond precision guarantees uniqueness across concurrent test
     * class instantiations.</p>
     */
    public static final DateTimeFormatter COMPACT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    /**
     * Date-only format: {@code yyyy-MM-dd}.
     *
     * <p>Reserved for future UI labels, report headers, and any context where
     * time-of-day is irrelevant.</p>
     */
    public static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * ISO-8601 instant with UTC offset: {@code yyyy-MM-dd'T'HH:mm:ss.SSSXXX}.
     *
     * <p>Used by {@link io.aledep10.nomadsync.logging.ClefFormatter} for the
     * CLEF {@code @t} field. Requires {@link OffsetDateTime} — not
     * {@link LocalDateTime} — because CLEF mandates a UTC offset for correct
     * cross-timezone event ordering in Seq dashboards.</p>
     *
     * <p>Example output: {@code 2026-05-20T12:30:00.123+02:00}</p>
     */
    public static final DateTimeFormatter ISO_INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    // ── Convenience factory methods ───────────────────────────────────────────

    /**
     * Returns the current local timestamp formatted with {@link #LOG}.
     *
     * @return current timestamp as {@code yyyy-MM-dd HH:mm:ss.SSS}
     */
    public static String nowLog() {
        return LocalDateTime.now().format(LOG);
    }

    /**
     * Returns the current local timestamp formatted with {@link #SNAPSHOT}.
     *
     * @return current timestamp as {@code yyyy-MM-dd_HH-mm}
     */
    public static String nowSnapshot() {
        return LocalDateTime.now().format(SNAPSHOT);
    }

    /**
     * Returns the current local timestamp formatted with {@link #COMPACT}.
     *
     * @return current timestamp as {@code yyyyMMdd_HHmmss_SSS}
     */
    public static String nowCompact() {
        return LocalDateTime.now().format(COMPACT);
    }
}