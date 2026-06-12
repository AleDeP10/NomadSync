package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.logging.ConsoleLogWriter;
import io.aledep10.nomadsync.logging.FileLogWriter;
import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.logging.LogWriter;
import io.aledep10.nomadsync.logging.SeqHttpLogWriter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.TreeSet;

/**
 * Levelled, append-only logging service with fan-out to multiple {@link LogWriter} targets.
 *
 * <h2>Writer configuration</h2>
 * <p>Writers are built from {@code log.writers} in the application properties
 * (comma-separated, default {@code "console,file"}). Supported tokens:</p>
 * <ul>
 *   <li>{@code console} — writes to stdout/stderr via {@link ConsoleLogWriter}</li>
 *   <li>{@code file}    — appends to {@code log.path} via {@link FileLogWriter};
 *       skipped with a warning if {@code log.path} is absent</li>
 *   <li>{@code seq}     — ships CLEF events to Seq via {@link SeqHttpLogWriter};
 *       skipped with a warning if {@code seq.url} is absent</li>
 * </ul>
 *
 * <h2>Vault scoping</h2>
 * <p>Each log entry carries a {@code repoSlug} ({@code <owner>/<name>}) that
 * identifies which vault generated the event. The system-level instance uses
 * {@code "SYSTEM"} as the slug. Per-vault instances are obtained via
 * {@link #withVault(String)} — they share the same underlying writers and
 * only differ in the slug written to each line.</p>
 *
 * <h2>Threading</h2>
 * <p>Thread safety is delegated to each {@link LogWriter} implementation:
 * {@link FileLogWriter} uses {@code synchronized}; {@link SeqHttpLogWriter}
 * uses a {@link java.util.concurrent.BlockingQueue}.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>{@link #close()} must be called at shutdown (typically from the JVM shutdown
 * hook in {@code Main}) to flush and release resources held by writers such as
 * {@link SeqHttpLogWriter}.</p>
 *
 * <h2>Constructor argument order</h2>
 * <p>Follows the project convention: {@link Properties} first, {@link LogService}
 * last — but since this class IS the log service, it takes no {@code LogService}
 * dependency.</p>
 */
public class LogService {

    private final Properties properties;
    private final List<LogWriter> writers;
    private final LogLevel minLevel;
    private final String repoSlug;

    // ── Public constructors ───────────────────────────────────────────────────

    /**
     * Constructs a system-level {@code LogService} with {@code repoSlug = "SYSTEM"}.
     *
     * <p>Writers are built from the {@code log.writers} property. Use this constructor
     * at boot, before any vault is loaded.</p>
     *
     * @param properties application properties containing at minimum {@code log.level}
     *                   and {@code log.writers}
     */
    public LogService(Properties properties) {
        this(properties, buildWriters(properties), "SYSTEM");
    }

    /**
     * Constructs a vault-scoped {@code LogService} with the given {@code repoSlug}.
     *
     * <p>Writers are built fresh from properties — use {@link #withVault(String)}
     * instead when you want to reuse existing writers without reopening files or
     * reconnecting to Seq.</p>
     *
     * @param properties application properties
     * @param repoSlug   vault identifier in {@code <owner>/<name>} form,
     *                   e.g. {@code AleDeP10/public-vault}
     */
    public LogService(Properties properties, String repoSlug) {
        this(properties, buildWriters(properties), repoSlug);
    }

    // ── Private constructor — single construction point ───────────────────────

    /**
     * Internal constructor — single construction point for all public constructors
     * and {@link #withVault(String)}.
     *
     * <p>{@link List#copyOf} is applied here for defensive immutability —
     * the caller cannot modify the writer list after construction regardless
     * of how the list was built.</p>
     */
    private LogService(Properties properties, List<LogWriter> writers, String repoSlug) {
        this.properties = properties;
        this.writers    = List.copyOf(writers);
        this.minLevel   = LogLevel.valueOf(properties.getProperty("log.level"));
        this.repoSlug   = repoSlug;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Returns a new {@code LogService} scoped to the given vault, reusing the
     * existing writers.
     *
     * <p>Writers are shared — no file is reopened, no Seq connection is
     * re-established, no daemon thread is restarted. Only the {@code repoSlug}
     * written to each log line changes.</p>
     *
     * @param repoSlug vault identifier in {@code <owner>/<name>} form
     * @return a new {@code LogService} instance scoped to the vault
     */
    public LogService withVault(String repoSlug) {
        return new LogService(this.properties, this.writers, repoSlug);
    }

    // ── API pubblica ──────────────────────────────────────────────────────────

    public void debug(String message)                  { log(LogLevel.DEBUG, message, null);  }
    public void debug(String message, Throwable cause) { log(LogLevel.DEBUG, message, cause); }
    public void info(String message)                   { log(LogLevel.INFO,  message, null);  }
    public void info(String message, Throwable cause)  { log(LogLevel.INFO,  message, cause); }
    public void warn(String message)                   { log(LogLevel.WARN,  message, null);  }
    public void warn(String message, Throwable cause)  { log(LogLevel.WARN,  message, cause); }
    public void error(String message)                  { log(LogLevel.ERROR, message, null);  }
    public void error(String message, Throwable cause) { log(LogLevel.ERROR, message, cause); }

    // ── Core ──────────────────────────────────────────────────────────────────

    private void log(LogLevel level, String message, Throwable cause) {
        if (level.compareTo(minLevel) < 0) return;
        writers.forEach(w -> w.write(level, repoSlug, message, cause));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Releases resources held by all registered writers.
     *
     * <p>Must be called once at JVM shutdown, typically from the shutdown hook
     * in {@code Main}. After this call, no further log operations should be
     * performed on this instance or any instance sharing the same writers.</p>
     */
    public void close() {
        writers.forEach(LogWriter::close);
    }

    // ── Writer factory ────────────────────────────────────────────────────────

    /**
     * Builds the list of {@link LogWriter} instances from the {@code log.writers}
     * property.
     *
     * <p>Tokens are parsed in alphabetical order (via {@link TreeSet}) to ensure
     * deterministic writer order across runs. Unknown tokens and missing required
     * properties are reported to {@code stderr} and skipped — the service starts
     * even if some writers cannot be initialised.</p>
     *
     * <p>{@code InMemoryLogWriter} is intentionally absent from the switch —
     * it is not a user-configurable writer but an in-process tool used by code
     * that needs to inspect log output (e.g. future tray UI buffering). It is
     * instantiated directly where needed, never via properties.</p>
     *
     * @param properties application properties
     * @return mutable list of initialised writers
     */
    private static List<LogWriter> buildWriters(Properties properties) {
        List<LogWriter> result = new ArrayList<>();
        Set<String> tokens = Arrays.stream(
                        properties.getProperty("log.writers", "console,file").split(","))
                .map(String::trim)
                .collect(Collectors.toCollection(TreeSet::new));

        tokens.forEach(token -> {
            switch (token) {
                case "console" -> result.add(new ConsoleLogWriter());
                case "file" -> {
                    if (properties.containsKey("log.path")) {
                        result.add(new FileLogWriter(
                                Path.of(properties.getProperty("log.path"))));
                    } else {
                        System.err.println("[LogService] log.path missing — file writer skipped");
                    }
                }
                case "seq" -> {
                    if (properties.containsKey("seq.url")) {
                        result.add(new SeqHttpLogWriter(
                                properties.getProperty("seq.url"),
                                properties.getProperty("seq.apiKey", "")));
                    } else {
                        System.err.println("[LogService] seq.url missing — seq writer skipped");
                    }
                }
                default -> System.err.println("[LogService] unknown writer: " + token);
            }
        });

        return result;
    }
}