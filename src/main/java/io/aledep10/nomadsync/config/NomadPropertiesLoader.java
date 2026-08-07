package io.aledep10.nomadsync.config;

import io.aledep10.nomadsync.exception.ConfigException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Resolves NomadSync configuration through a two-layer cascade:
 * {@code installProperties} (always present, the installation's own
 * {@code config.properties}, resolved once at construction) overridden by
 * {@code workspaceProperties} (optional, a specific workspace's own
 * {@code config.properties}, loaded on demand via {@link #loadWorkspaceOverrides}
 * once a workspace target is known).
 *
 * <h2>Why an instance, not a static utility</h2>
 * <p>The classpath-only static loader this replaces could never reflect a
 * workspace override — every reader would need to be re-pointed at a
 * different source the moment a workspace becomes known partway through
 * {@code Main}'s bootstrap. An instance, constructed once and passed to every
 * consumer ({@code LogService}, {@code MarkerService}, {@code GitService}, ...),
 * lets {@link #loadWorkspaceOverrides} take effect for every reader
 * retroactively — no consumer caches a value read before the override was
 * applied, because every accessor here re-reads the current merged view on
 * every call.</p>
 *
 * <h2>Merge semantics</h2>
 * <p>{@link #getProperties()} is {@code installProperties} with every key
 * present in {@code workspaceProperties} overwritten on top — not a
 * three-way merge, a workspace override always wins outright, whole-value,
 * never combined with the install value for the same key.</p>
 *
 * <h2>No {@code LogService} dependency — deliberate</h2>
 * <p>{@code LogService} itself is constructed from a resolved
 * {@code NomadPropertiesLoader} (see {@code LogService}'s own Javadoc) — taking
 * a {@code LogService} here would create a circular bootstrap dependency.
 * Loader-internal diagnostics use {@code System.out}/{@code System.err}
 * directly, the same convention already used elsewhere in {@code Main} for
 * output that must be possible before any service exists yet.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>One instance per process, constructed once in {@code Main}'s bootstrap
 * and shared by every consumer for the remainder of that one-shot invocation
 * ({@code NomadSync-EVT-013}) — not designed to be re-pointed at a
 * <em>different</em> installation directory after construction, nor to detect
 * a workspace switch made by another process while a future daemon consumer
 * (Tray, v2+) is running. That case needs a separate, cross-process
 * notification mechanism, not a repointing method on this class — tracked
 * separately, not solved here.</p>
 */
public class NomadPropertiesLoader {

    public static final String INSTALL_CONFIG_FILE_NAME = "installConfig.properties";
    public static final String WORKSPACE_CONFIG_FILE_NAME = "config.properties";

    private final Path installDir;
    private final Properties installProperties;
    private Properties workspaceProperties;
    private Properties merged;
    private boolean workspaceOverridesFound;

    /**
     * Loads {@code installProperties} from {@code <installDir>/config.properties}
     * — mandatory, an installation without a readable one cannot proceed.
     *
     * @param installDir the NomadSync installation directory (where the jar and
     *                   {@code config.properties} live)
     * @throws ConfigException if {@code config.properties} is absent from
     *                         {@code installDir}, or exists but cannot be read
     */
    public NomadPropertiesLoader(Path installDir) throws ConfigException {
        this.installDir = installDir;
        Path file = installDir.resolve(INSTALL_CONFIG_FILE_NAME);
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            throw new ConfigException("Unable to load install configuration at " + file
                    + ": " + e.getMessage(), e);
        }
        this.installProperties = properties;
        this.workspaceProperties = new Properties();
        recomputeMerged();
        System.out.println("NomadPropertiesLoader - installProperties loaded from " + file);
    }

    /**
     * Support constructor for {@link #forTesting(Properties)} — initialises
     * both layers empty, populated by the caller immediately after.
     */
    private NomadPropertiesLoader() {
        this.installDir = null;
        this.installProperties = new Properties();
        this.workspaceProperties = new Properties();
        recomputeMerged();
    }

    /**
     * Test-only factory — builds a loader directly from an in-memory
     * {@link Properties} instance, bypassing filesystem I/O entirely. For tests
     * that only need {@link #getProperties()}/{@link #getInt}/etc. to return
     * pre-arranged values, not a real install directory on disk.
     *
     * <p>Not a second public constructor with the same shape as the real one on
     * purpose — a factory with an explicit, mismatched name is far less likely
     * to be reached for by production code via autocomplete than a constructor
     * overload would be.</p>
     *
     * @param properties the values to expose as {@code installProperties};
     *                   copied, not retained by reference — later mutation of
     *                   the argument has no effect on the loader
     * @return a loader with no workspace overrides applied yet
     */
    public static NomadPropertiesLoader forTesting(Properties properties) {
        NomadPropertiesLoader loader = new NomadPropertiesLoader();
        loader.installProperties.putAll(properties);
        loader.recomputeMerged();
        return loader;
    }

    /**
     * Writes a brand-new workspace {@code config.properties} at the given
     * location, populated with {@code initialValues} — used by
     * {@code workspace create} to scaffold configuration for a workspace that
     * has just come into existence.
     *
     * <p>Deliberately independent of this loader's own install/workspace
     * cascade state: the workspace being scaffolded here is not necessarily the
     * one this loader instance is currently scoped to — {@code workspace}
     * subcommands run before {@code Main} ever resolves or loads overrides for
     * any workspace at all.</p>
     *
     * @param workspaceConfigDir the new workspace's marker directory
     *                           ({@code .nomadsync-workspace/})
     * @param initialValues      values to write — may be empty; an empty file is
     *                           still written, so {@link #hasWorkspaceOverrides()}
     *                           is well-defined the moment this workspace is
     *                           later loaded, rather than ambiguous between
     *                           "never scaffolded" and "scaffolded with nothing"
     * @throws ConfigException if the file cannot be written
     */
    public void createWorkspaceProperties(Path workspaceConfigDir, Map<String, String> initialValues)
            throws ConfigException {
        Properties properties = new Properties();
        properties.putAll(initialValues);
        Path file = workspaceConfigDir.resolve(WORKSPACE_CONFIG_FILE_NAME);
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "NomadSync workspace configuration");
        } catch (IOException e) {
            throw new ConfigException("Unable to create workspace configuration at " + file, e);
        }
    }

    /**
     * Loads (or replaces) {@code workspaceProperties} from
     * {@code <workspaceConfigDir>/config.properties}, then recomputes the
     * merged view returned by {@link #getProperties()}.
     *
     * <p>A missing file is not an error — a workspace with no
     * {@code config.properties} of its own (not yet scaffolded, or never
     * needed one) simply contributes no overrides; every reader falls through
     * to {@code installProperties} for every key. A file that exists but
     * cannot be read <strong>is</strong> an error — silently ignoring a
     * present-but-corrupt override would apply install defaults the user
     * explicitly meant to override, without any indication why.</p>
     *
     * <h2>Install-only keys are filtered, not fatal</h2>
     * <p>Any key classified {@link ConfigLevelRegistry.ConfigLevel#INSTALL_ONLY}
     * found in this file (e.g. {@code log.*}, {@code socket.*}, machine-level
     * settings that have no legitimate per-workspace meaning) is reported to
     * {@code stderr} and dropped from the loaded overrides — the rest of the
     * file's legitimate keys still apply. Not fatal: the system cannot tell an
     * honest mistake from a deliberate (if unsupported) override, so it warns
     * rather than blocking the workspace from loading at all.</p>
     *
     * @param workspaceConfigDir the workspace's marker directory
     *                           ({@code .nomadsync-workspace/}), not the
     *                           workspace root itself
     * @throws ConfigException if {@code config.properties} exists at that location
     *                         but cannot be read
     */
    public void loadWorkspaceOverrides(Path workspaceConfigDir) throws ConfigException {
        Path file = workspaceConfigDir.resolve(WORKSPACE_CONFIG_FILE_NAME);
        Properties overrides = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                overrides.load(in);
            } catch (IOException e) {
                throw new ConfigException("Unable to load workspace configuration at " + file
                        + ": " + e.getMessage(), e);
            }
            this.workspaceOverridesFound = true;
            System.out.println("NomadPropertiesLoader - workspaceProperties loaded from " + file);
        } else {
            this.workspaceOverridesFound = false;
            System.out.println("NomadPropertiesLoader - no config.properties at " + file
                    + " - no workspace overrides applied");
        }
        Set<String> alienKeys = ConfigLevelRegistry.findInstallOnlyKeys(overrides.stringPropertyNames());
        if (!alienKeys.isEmpty()) {
            System.err.println("[NomadPropertiesLoader] " + file + " contains install-only keys, ignored here: "
                    + String.join(", ", alienKeys) + " - move them to installConfig.properties");
            alienKeys.forEach(overrides::remove);
        }
        this.workspaceProperties = overrides;
        recomputeMerged();
    }

    /**
     * Reports whether the most recent {@link #loadWorkspaceOverrides} call found
     * and loaded a real {@code config.properties} — not whether any override was
     * "expected" or "correct", a judgment this class has no basis to make (a
     * workspace with no override file looks identical whether that absence is
     * deliberate or accidental — the file system carries no history). Purely
     * factual: {@code false} means every value currently returned by
     * {@link #getProperties()} came from {@code installProperties} alone.
     *
     * @return {@code true} if a workspace config.properties was found and loaded,
     *         {@code false} if none was found, or {@link #loadWorkspaceOverrides}
     *         has never been called
     */
    public boolean hasWorkspaceOverrides() {
        return workspaceOverridesFound;
    }

    /**
     * Returns the current merged view — {@code installProperties} with every
     * {@code workspaceProperties} key overwritten on top. A defensive copy: the
     * caller may read freely but cannot mutate this loader's internal state
     * through the returned instance — the only way to change a value is
     * {@link #setInstallProperty} (install layer) or {@link #loadWorkspaceOverrides}
     * (workspace layer), each of which recomputes this view immediately after.
     *
     * @return a copy of the merged properties; never {@code null}
     */
    public Properties getProperties() {
        Properties copy = new Properties();
        copy.putAll(merged);
        return copy;
    }

    /**
     * Reads a single string property, returning {@code defaultValue} if the
     * key is absent or blank.
     *
     * @param key          the property key; must not be {@code null}
     * @param defaultValue the fallback value
     * @return the property value, or {@code defaultValue} if absent or blank
     */
    public String get(String key, String defaultValue) {
        String value = merged.getProperty(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    /**
     * Reads an integer property.
     *
     * <p>Returns {@code defaultValue} if the key is absent, blank, or does not
     * parse as an integer.</p>
     *
     * @param key          the property key; must not be {@code null}
     * @param defaultValue the fallback value
     * @return the parsed integer, or {@code defaultValue} if absent, blank, or unparsable
     */
    public int getInt(String key, int defaultValue) {
        String value = merged.getProperty(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("[NomadPropertiesLoader] unrecognised integer value '" + value
                    + "' for key '" + key + "' - using default '" + defaultValue + "'");
            return defaultValue;
        }
    }
    /**
     * Reads a long property.
     *
     * <p>Returns {@code defaultValue} if the key is absent, blank, or does not
     * parse as a long.</p>
     *
     * @param key          the property key; must not be {@code null}
     * @param defaultValue the fallback value
     * @return the parsed long, or {@code defaultValue} if absent, blank, or unparsable
     */
    public long getLong(String key, long defaultValue) {
        String value = merged.getProperty(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("[NomadPropertiesLoader] unrecognised long value '" + value
                    + "' for key '" + key + "' - using default '" + defaultValue + "'");
            return defaultValue;
        }
    }

    /**
     * Reads a boolean property.
     *
     * <p>Returns {@code defaultValue} if the key is absent, blank, or not a
     * recognised boolean string ({@code "true"}/{@code "false"}, case-insensitive).</p>
     *
     * @param key          the property key; must not be {@code null}
     * @param defaultValue the fallback value
     * @return the parsed boolean, or {@code defaultValue} if absent, blank, or unrecognised
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = merged.getProperty(key);
        if (value == null || value.isBlank())    return defaultValue;
        if ("true".equalsIgnoreCase(value))      return true;
        if ("false".equalsIgnoreCase(value))     return false;
        System.err.println("[NomadPropertiesLoader] unrecognised boolean value '" + value
                + "' for key '" + key + "' - using default '" + defaultValue + "'");
        return defaultValue;
    }

    /**
     * Reads an enum property.
     *
     * <p>Returns {@code defaultValue} if the key is absent, blank, or does not
     * match any constant of {@code enumType} (case-insensitive comparison).</p>
     *
     * @param <E>          the enum type
     * @param key          the property key; must not be {@code null}
     * @param enumType     the enum class; must not be {@code null}
     * @param defaultValue the fallback value
     * @return the matching enum constant, or {@code defaultValue} if absent, blank, or unrecognised
     */
    public <E extends Enum<E>> E getEnum(String key, Class<E> enumType, E defaultValue) {
        String value = merged.getProperty(key);
        if (value == null || value.isBlank()) return defaultValue;
        for (E constant : enumType.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value)) return constant;
        }
        System.err.println("[NomadPropertiesLoader] unrecognised value '" + value
                + "' for key '" + key + "' - using default '" + defaultValue + "'");
        return defaultValue;
    }

    /**
     * Sets a single key in {@code installProperties} — never
     * {@code workspaceProperties}, which this loader only ever reads
     * ({@link #loadWorkspaceOverrides}), never writes. Recomputes the merged
     * view immediately, so every reader sees the change without a save/reload
     * round-trip.
     *
     * @param key   the property key
     * @param value the new value
     */
    public void setInstallProperty(String key, String value) {
        installProperties.setProperty(key, value);
        recomputeMerged();
    }

    /**
     * Persists {@code installProperties} — exactly the layer this loader owns —
     * to {@code <installDir>/installConfig.properties}. Never touches
     * {@code workspaceProperties}; there is no write path for those here by
     * design (workspace-level configuration is written by {@code workspace
     * create}, and by the pending {@code workspace config} sub-command — never
     * through this loader's own API).
     *
     * @throws ConfigException      if the file cannot be written
     * @throws IllegalStateException if this instance was built via
     *          {@link #forTesting(Properties)} — no real install directory to
     *          write to
     */
    public void saveInstallProperties() throws ConfigException {
        if (installDir == null) {
            throw new IllegalStateException("saveInstallProperties() requires a loader backed by a real "
                    + "install directory - not available on a forTesting() instance");
        }
        Path file = installDir.resolve(INSTALL_CONFIG_FILE_NAME);
        try (OutputStream out = Files.newOutputStream(file)) {
            installProperties.store(out, "NomadSync configuration - updated by 'NomadSync config'");
        } catch (IOException e) {
            throw new ConfigException("Unable to persist install configuration at " + file, e);
        }
    }

    /**
     * Merges {@code updates} into an existing workspace {@code config.properties}
     * — unlike {@link #createWorkspaceProperties}, which always writes a fresh
     * file, this reads whatever is already there first and only overwrites the
     * given keys, leaving every other existing key untouched.
     *
     * @param workspaceConfigDir the workspace's marker directory
     *                           ({@code .nomadsync-workspace/})
     * @param updates            keys to set or overwrite; must not be empty —
     *                           callers should not invoke this with nothing to
     *                           write
     * @throws ConfigException if the existing file cannot be read, or the
     *                         updated file cannot be written
     */
    public void updateWorkspaceProperties(Path workspaceConfigDir, Map<String, String> updates)
            throws ConfigException {
        Path file = workspaceConfigDir.resolve(WORKSPACE_CONFIG_FILE_NAME);
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException e) {
                throw new ConfigException("Unable to load existing workspace configuration at " + file, e);
            }
        }
        properties.putAll(updates);
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "NomadSync workspace configuration");
        } catch (IOException e) {
            throw new ConfigException("Unable to update workspace configuration at " + file, e);
        }
    }

    private void recomputeMerged() {
        Properties result = new Properties();
        result.putAll(installProperties);
        result.putAll(workspaceProperties);
        this.merged = result;
    }
}