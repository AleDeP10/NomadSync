package io.aledep10.nomadsync.logging;

import io.aledep10.nomadsync.service.LogService;

/**
 * Log severity levels used by {@link LogService}.
 *
 * <p>Levels are ordered from least to most severe. {@code LogService} writes a message
 * only if its level is greater than or equal to the configured minimum level.</p>
 *
 * <p>Declaring this enum in the {@code logging} package makes it available to all
 * logging infrastructure without introducing cross-package dependency cycles.</p>
 *
 * <h2>Severity order</h2>
 * <pre>DEBUG &lt; INFO &lt; WARN &lt; ERROR</pre>
 *
 * <h2>Usage in properties</h2>
 * <pre>{@code
 * // writing
 * properties.setProperty("log.level", LogLevel.INFO.name());
 *
 * // reading
 * LogLevel level = LogLevel.valueOf(properties.getProperty("log.level"));
 * }</pre>
 *
 * <h2>Seq mapping</h2>
 * <p>When events are forwarded to Seq via
 * {@link SeqHttpLogWriter}, each level maps to the corresponding
 * Seq severity string via {@link #toClef()}.</p>
 */
public enum LogLevel {

    /** Fine-grained diagnostic information — development and troubleshooting only. */
    DEBUG,

    /** General operational messages confirming the system is working as expected. */
    INFO,

    /** Potentially harmful situations that do not prevent normal operation. */
    WARN,

    /** Serious failures that require immediate attention. */
    ERROR;

    /**
     * Returns the Seq CLEF severity string corresponding to this level.
     *
     * <p>Seq uses different names than the standard Java logging conventions.
     * This mapping ensures events appear in the correct severity bucket in
     * Seq dashboards and can be filtered using Seq's query language.</p>
     *
     * <table border="1">
     *   <caption>Level mapping</caption>
     *   <tr><th>NomadSync</th><th>Seq CLEF</th></tr>
     *   <tr><td>DEBUG</td><td>Debug</td></tr>
     *   <tr><td>INFO</td><td>Information</td></tr>
     *   <tr><td>WARN</td><td>Warning</td></tr>
     *   <tr><td>ERROR</td><td>Error</td></tr>
     * </table>
     *
     * @return the Seq severity string for use in the {@code @l} CLEF field
     */
    public String toClef() {
        return switch (this) {
            case DEBUG -> "Debug";
            case INFO  -> "Information";
            case WARN  -> "Warning";
            case ERROR -> "Error";
        };
    }
}