package io.aledep10.nomadsync.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Utility class for loading {@code config.properties} from the classpath.
 *
 * <p>Centralises all classpath resource access for NomadSync configuration,
 * eliminating duplicated try-with-resources blocks across the codebase.</p>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Returns an empty {@link Properties} object if {@code config.properties}
 *       is absent from the classpath — callers receive their built-in defaults.</li>
 *   <li>Returns an empty {@link Properties} object if the file exists but cannot
 *       be read — logs a {@code WARNING} and falls back gracefully.</li>
 *   <li>Never returns {@code null}.</li>
 *   <li>Never throws — all exceptions are caught and logged internally.</li>
 * </ul>
 *
 * <h2>Classpath vs filesystem</h2>
 * <p>This loader reads {@code config.properties} from the classpath root —
 * useful for defaults bundled inside the JAR. At runtime, the production
 * configuration file is loaded from the filesystem via {@link java.io.FileInputStream}
 * in {@code Main}. These two sources are complementary: classpath provides
 * built-in defaults; filesystem provides the user's environment-specific overrides.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Properties props = NomadPropertiesLoader.load();
 * String level = props.getProperty(NomadProperties.Log.LEVEL, LogLevel.INFO.name());
 *
 * // single-value convenience methods
 * boolean debug   = NomadPropertiesLoader.getBoolean(NomadProperties.Log.LEVEL, false);
 * LogLevel level2 = NomadPropertiesLoader.getEnum(
 *         NomadProperties.Log.LEVEL, LogLevel.class, LogLevel.INFO);
 * }</pre>
 *
 * <p>Non-instantiable — all members are {@code static}.</p>
 */
public final class NomadPropertiesLoader {

    private static final System.Logger LOGGER =
            System.getLogger(NomadPropertiesLoader.class.getName());

    private static final String FILE_PATH = "/config.properties";

    private NomadPropertiesLoader() {}

    /**
     * Loads {@code config.properties} from the classpath root.
     *
     * <p>If the file is absent or unreadable, an empty {@link Properties} object
     * is returned — callers will use their built-in defaults.
     *
     * @return a {@link Properties} instance populated from {@code config.properties},
     *         or an empty instance if the file is absent or unreadable; never {@code null}
     */
    public static Properties load() {
        Properties properties = new Properties();

        try (InputStream stream =
                     NomadPropertiesLoader.class.getResourceAsStream(FILE_PATH)) {

            if (stream == null) {
                return properties;
            }
            properties.load(stream);

        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "config.properties not readable - using built-in defaults", e);
        }

        return properties;
    }

    /**
     * Reads a single string property, returning {@code null} if the key is absent.
     *
     * <p>Convenience wrapper over {@link Properties#getProperty(String)} for callers
     * that need a single value without loading the full file multiple times.
     * Prefer {@link #load()} when reading more than one property.</p>
     *
     * @param key the property key; must not be {@code null}
     * @return the property value, or {@code null} if absent
     */
    public static String get(String key) {
        return load().getProperty(key);
    }

    /**
     * Reads a single string property, returning {@code defaultValue} if the key
     * is absent.
     *
     * @param key          the property key; must not be {@code null}
     * @param defaultValue the fallback value if the key is absent
     * @return the property value, or {@code defaultValue} if absent
     */
    public static String get(String key, String defaultValue) {
        return load().getProperty(key, defaultValue);
    }

    /**
     * Reads a boolean property.
     *
     * <p>Returns {@code defaultValue} if the key is absent or the value is not a
     * recognised boolean string ({@code "true"} or {@code "false"},
     * case-insensitive).</p>
     *
     * @param key          the property key; must not be {@code null}
     * @param defaultValue the fallback value
     * @return the parsed boolean, or {@code defaultValue} if absent or unrecognised
     */
    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = load().getProperty(key);
        if (value == null)                      return defaultValue;
        if ("true".equalsIgnoreCase(value))     return true;
        if ("false".equalsIgnoreCase(value))    return false;
        LOGGER.log(System.Logger.Level.WARNING,
                "config.properties: unrecognised boolean value ''{0}'' for key ''{1}'' " +
                        "- using default ''{2}''", value, key, defaultValue);
        return defaultValue;
    }

    /**
     * Reads an enum property.
     *
     * <p>Returns {@code defaultValue} if the key is absent or the value does not
     * match any constant of the given enum type (case-insensitive comparison).</p>
     *
     * @param <E>          the enum type
     * @param key          the property key; must not be {@code null}
     * @param enumType     the enum class; must not be {@code null}
     * @param defaultValue the fallback value
     * @return the matching enum constant, or {@code defaultValue} if absent or unrecognised
     */
    public static <E extends Enum<E>> E getEnum(String key, Class<E> enumType, E defaultValue) {
        String value = load().getProperty(key);
        if (value == null) return defaultValue;
        for (E constant : enumType.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value)) return constant;
        }
        LOGGER.log(System.Logger.Level.WARNING,
                "config.properties: unrecognised value ''{0}'' for key ''{1}'' " +
                        "- using default ''{2}''", value, key, defaultValue);
        return defaultValue;
    }
}