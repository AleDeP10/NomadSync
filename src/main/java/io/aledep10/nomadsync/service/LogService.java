package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.config.NomadProperties;
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
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Levelled, append-only logging service with fan-out to multiple {@link LogWriter} targets.
 *
 * <h2>Writer configuration</h2>
 * <p>Writers are built from the {@link NomadProperties.Log#WRITERS} property
 * (comma-separated, default {@code "console,file"}). Supported tokens:</p>
 * <ul>
 *   <li>{@code console} — writes to stdout/stderr via {@link ConsoleLogWriter}</li>
 *   <li>{@code file}    — appends to {@link NomadProperties.Log#PATH} via
 *       {@link FileLogWriter}; skipped with a warning if the key is absent</li>
 *   <li>{@code seq}     — ships CLEF events to Seq via {@link SeqHttpLogWriter};
 *       skipped with a warning if {@link NomadProperties.Log#SEQ_URL} is absent</li>
 * </ul>
 *
 * <h2>Vault scoping</h2>
 * <p>Each log entry carries a {@code repoSlug} ({@code <owner>/<name>}) identifying
 * the vault that generated the event. The system-level instance uses {@code "SYSTEM"}.
 * Per-vault instances are obtained via {@link #withVault(String)} — they share the
 * same underlying writers and only differ in the slug written to each line.</p>
 *
 * <h2>Configuration loading</h2>
 * <p>At boot, {@code Main} loads {@code config.properties} from the filesystem via
 * {@link java.io.FileInputStream} and passes the resulting {@link Properties} here.
 * Built-in defaults are available via {@link io.aledep10.nomadsync.config.NomadPropertiesLoader},
 * which reads from the classpath — the two sources are complementary.
 * All property keys are declared as constants in
 * {@link io.aledep10.nomadsync.config.NomadProperties.Log}.</p>
 *
 * <p>Thread safety is delegated to each {@link LogWriter} implementation:
 * {@link FileLogWriter} uses {@code synchronized}; {@link SeqHttpLogWriter}
 * uses a {@link java.util.concurrent.BlockingQueue}.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>{@link #close()} must be called at shutdown (typically from the JVM shutdown
 * hook in {@code Main}) to flush and release resources held by writers such as
 * {@link SeqHttpLogWriter}.</p>
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
     * <p>Writers are built from {@link NomadProperties.Log#WRITERS}.
     * Use this constructor at boot, before any vault is loaded.</p>
     *
     * @param properties application properties — must contain at minimum
     *                   {@link NomadProperties.Log#LEVEL} and
     *                   {@link NomadProperties.Log#WRITERS}
     */
    public LogService(Properties properties) {
        this(properties, buildWriters(properties), "SYSTEM");
    }

    /**
     * Constructs a vault-scoped {@code LogService} with the given {@code repoSlug}.
     *
     * <p>Writers are built fresh from properties. Prefer {@link #withVault(String)}
     * when deriving a per-vault instance from an existing one — it reuses writers
     * without reopening files or reconnecting to Seq.</p>
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
        this.minLevel   = LogLevel.valueOf(
                properties.getProperty(NomadProperties.Log.LEVEL, LogLevel.INFO.name()));
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

    // ── API ───────────────────────────────────────────────────────────────────

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
     * Builds the list of {@link LogWriter} instances from the
     * {@link NomadProperties.Log#WRITERS} property.
     *
     * <p>Tokens are parsed in alphabetical order (via {@link TreeSet}) to ensure
     * deterministic writer initialisation across runs. Unknown tokens and missing
     * required properties ({@link NomadProperties.Log#PATH} for {@code file},
     * {@link NomadProperties.Log#SEQ_URL} for {@code seq}) are reported to
     * {@code stderr} and skipped — the service starts even if some writers cannot
     * be initialised.</p>
     *
     * <p>{@code InMemoryLogWriter} is intentionally absent — it is not a
     * user-configurable writer but an in-process tool instantiated directly by
     * code that needs to inspect log output at runtime (e.g. tray UI buffering).</p>
     *
     * @param properties application properties
     * @return mutable list of initialised writers
     */
    private static List<LogWriter> buildWriters(Properties properties) {
        List<LogWriter> result = new ArrayList<>();
        Set<String> tokens = Arrays.stream(
                        properties.getProperty(NomadProperties.Log.WRITERS, "console,file")
                                .split(","))
                .map(String::trim)
                .collect(Collectors.toCollection(TreeSet::new));

        tokens.forEach(token -> {
            switch (token) {
                case "console" -> result.add(new ConsoleLogWriter());
                case "file" -> {
                    if (properties.containsKey(NomadProperties.Log.PATH)) {
                        result.add(new FileLogWriter(
                                Path.of(properties.getProperty(NomadProperties.Log.PATH))));
                    } else {
                        System.err.println("[LogService] "
                                + NomadProperties.Log.PATH
                                + " missing — file writer skipped");
                    }
                }
                case "seq" -> {
                    if (properties.containsKey(NomadProperties.Log.SEQ_URL)) {
                        result.add(new SeqHttpLogWriter(
                                properties.getProperty(NomadProperties.Log.SEQ_URL),
                                properties.getProperty(NomadProperties.Log.SEQ_API_KEY, "")));
                    } else {
                        System.err.println("[LogService] "
                                + NomadProperties.Log.SEQ_URL
                                + " missing — seq writer skipped");
                    }
                }
                default -> System.err.println("[LogService] unknown writer: " + token);
            }
        });

        return result;
    }
}