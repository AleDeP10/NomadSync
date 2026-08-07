package io.aledep10.nomadsync.util;

import io.aledep10.nomadsync.service.LogService;

import java.nio.file.Path;
import java.util.Properties;

/**
 * Utility class for reading {@link java.util.Properties} with blank-safe fallback semantics.
 *
 * <p>The standard {@link java.util.Properties#getProperty(String, String)} returns a blank
 * string when the key exists but has no value — the default is ignored. Every method in
 * this class treats a blank value the same as an absent key, always falling back to the
 * supplied default.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // replaces: properties.getProperty("git.executable", "git")
 * String exe = PropertiesUtil.get(properties, NomadProperties.Git.EXECUTABLE, "git");
 *
 * // replaces: Long.parseLong(properties.getProperty("autosave.intervalMinutes", "15"))
 * long interval = PropertiesUtil.getLong(properties, NomadProperties.Autosave.INTERVAL_MINUTES, 15L);
 *
 * // resolves a path.* / log.* property against the directory containing the
 * // config.properties file in use — not the process's working directory —
 * // whether the key is absent, blank, or present with a relative value
 * Path vaultsFile = PropertiesUtil.resolvePath(properties, NomadProperties.Path.VAULTS,
 *         "catalog.json", configDir, logService);
 * }</pre>
 *
 * <p>Non-instantiable — all members are {@code static}.</p>
 */
public final class PropertiesUtil {

    private PropertiesUtil() {}

    /**
     * Returns the property value if present and non-blank,
     * or {@code defaultValue} otherwise.
     * Prevents the standard {@link java.util.Properties#getProperty(String, String)}
     * from returning a blank string when the key exists but has no value.
     */
    public static String get(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key);
        return StringUtil.isNonBlank(value) ? value : defaultValue;
    }

    /**
     * Parses a long property with a default fallback on blank or missing value.
     */
    public static long getLong(Properties properties, String key, long defaultValue) {
        String value = properties.getProperty(key);
        if (StringUtil.isBlank(value)) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Parses an int property with a default fallback on blank or missing value.
     */
    public static int getInt(Properties properties, String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (StringUtil.isBlank(value)) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Resolves a {@code path.*}/{@code log.*} property value (or its default, if
     * absent/blank) against {@code configDir}, logging the outcome at
     * {@code DEBUG} whenever resolution actually did something worth knowing —
     * a defaulted value, or an explicit value that was relative.
     *
     * @param properties      application properties
     * @param key             the property key to resolve (e.g. {@code log.filePath})
     * @param defaultRelative fallback value (relative to {@code configDir}) used
     *                        when {@code key} is absent or blank
     * @param configDir       directory containing the {@code config.properties}
     *                        file in use — base for resolving a relative value
     * @param logService      logger for the resolution outcome
     * @return the resolved, absolute {@link Path}
     */
    public static Path resolvePath(Properties properties, String key, String defaultRelative,
                                   Path configDir, LogService logService) {
        String explicit = properties.getProperty(key);
        boolean isDefaulted = StringUtil.isBlank(explicit);
        String raw = isDefaulted ? defaultRelative : explicit;
        Path path = Path.of(raw);
        Path resolved = path.isAbsolute() ? path : configDir.resolve(path).normalize();
        if (isDefaulted) {
            logService.debug(key + " not set, defaulting to " + resolved);
        } else if (!path.isAbsolute()) {
            logService.debug(key + "='" + raw + "' is relative - resolved to " + resolved);
        }
        return resolved;
    }

    /**
     * Resolves a {@code path.*}/{@code log.*} property value (or its default, if
     * absent/blank) against {@code configDir}, without logging the outcome.
     *
     * <p>Intended for bootstrap contexts where no {@link LogService} instance
     * exists yet — most notably resolving {@code log.filePath} itself while
     * {@code LogService} is still constructing its own writers. Prefer the
     * {@link #resolvePath(Properties, String, String, Path, LogService)} overload
     * everywhere a {@code LogService} is already available, so the resolution is
     * never silent.</p>
     *
     * @return the resolved, absolute {@link Path}
     */
    public static Path resolvePath(Properties properties, String key,
                                   String defaultRelative, Path configDir) {
        String explicit = properties.getProperty(key);
        boolean isDefaulted = StringUtil.isBlank(explicit);
        Path path = Path.of(isDefaulted ? defaultRelative : explicit);
        return path.isAbsolute() ? path : configDir.resolve(path).normalize();
    }
}