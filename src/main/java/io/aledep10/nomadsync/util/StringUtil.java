package io.aledep10.nomadsync.util;

/**
 * Utility class providing common string operations used across the ForgeUI component hierarchy.
 *
 * <p>Centralises null-safe string handling, eliminating scattered {@code null} checks
 * and ternary expressions throughout setter implementations.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * helperText.set(StringUtil.nullToEmpty(value));
 * if (StringUtil.isBlank(input)) { ... }
 * }</pre>
 */
public final class StringUtil {

    // Non-instantiable utility class.
    private StringUtil() {
    }

    /**
     * Returns {@code true} if the string is {@code null} or contains only whitespace.
     *
     * @param str the string to test; may be {@code null}
     * @return {@code true} if {@code str} is {@code null} or blank
     */
    public static boolean isBlank(String str) {
        return str == null || str.isBlank();
    }

    /**
     * Returns {@code true} if the string is neither {@code null} nor blank.
     *
     * @param str the string to test; may be {@code null}
     * @return {@code true} if {@code str} is non-null and not blank
     */
    public static boolean isNonBlank(String str) {
        return str != null && !str.isBlank();
    }

    /**
     * Returns the given string, or an empty string if it is {@code null}.
     *
     * <p>Used in optional property setters where {@code null} semantically means
     * "clear the value" rather than "this is an error".
     *
     * @param str the string to normalise; may be {@code null}
     * @return {@code str} if non-null, otherwise {@code ""}
     */
    public static String nullToEmpty(String str) {
        return str == null ? "" : str;
    }

    /**
     * Returns the first non-null value in the given sequence,
     * or {@code null} if all values are null.
     *
     * <p>Intended for credential resolution chains where vault-level
     * values take precedence over global config, which in turn takes
     * precedence over the system Git configuration:
     * <pre>
     *   StringUtil.coalesce(vault.getGitToken(),
     *                        properties.getProperty("git.token"))
     * </pre>
     *
     * @param values the values to evaluate in order
     * @return the first non-null value, or {@code null}
     */
    public static String coalesce(String... values) {
        for (String v : values) {
            if (v != null) return v;
        }
        return null;
    }
}