package io.aledep10.nomadsync.util;

import org.jetbrains.annotations.NotNull;

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
     * Returns the first non-null value in the given sequence.
     *
     * <p>Intended for credential resolution chains where vault-level
     * values take precedence over global config, which in turn takes
     * precedence over the system Git configuration:
     * <pre>{@code
     * String token = StringUtil.coalesce(vault.getGitToken(),
     *                                    PropertiesUtil.get(properties, "git.token", null));
     * }</pre>
     *
     * <p>All values being {@code null} is considered a caller error —
     * the last argument should always be a non-null default. If all
     * values are {@code null}, a {@link NullPointerException} is thrown
     * to surface the misconfiguration early rather than propagating
     * {@code null} silently through the call chain.</p>
     *
     * @param values the values to evaluate in order; at least one must be non-null
     * @return the first non-null value
     * @throws NullPointerException if all values are {@code null}
     */
    public static @NotNull String coalesce(String... values) {
        for (String v : values) {
            if (v != null) return v;
        }
        throw new NullPointerException("coalesce: all values are null - last argument must be a non-null default");
    }



    /**
     * Computes the Levenshtein edit distance between two strings — the minimum
     * number of single-character insertions, deletions, or substitutions needed
     * to transform one into the other. Case-insensitive, since flag names are
     * conventionally lowercase and a typo shouldn't hide behind a case mismatch.
     *
     * @param a first string
     * @param b second string
     * @return the edit distance, always {@code >= 0}
     */
    public static int levenshteinDistance(String a, String b) {
        String s1 = a.toLowerCase();
        String s2 = b.toLowerCase();
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(
                                dp[i - 1][j] + 1,      // deletion
                                dp[i][j - 1] + 1),     // insertion
                        dp[i - 1][j - 1] + cost); // substitution
            }
        }
        return dp[s1.length()][s2.length()];
    }
}