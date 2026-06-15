package io.aledep10.nomadsync.util;

import java.util.Objects;

/**
 * Utility class providing guard methods for common argument validation patterns.
 *
 * <p>Centralises null-checks and range-checks across the ForgeUI component hierarchy,
 * replacing ad-hoc {@code if (x == null) throw ...} blocks with self-documenting
 * one-liners that name both the condition and the offending parameter.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ValidationUtil.requireNonNull(label, "label");
 * ValidationUtil.requireNonBlank(argument, "<argument>");
 * }</pre>
 */
public final class ValidationUtil {

    // Non-instantiable utility class.
    private ValidationUtil() {}

    /**
     * Throws {@link IllegalArgumentException} if {@code value} is {@code null}.
     *
     * <p>Prefer this over {@link Objects#requireNonNull} when the caller contract
     * specifies {@code IllegalArgumentException} rather than {@code NullPointerException}.
     *
     * @param value the value to check
     * @param field the parameter name, used in the exception message
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
    }

    /**
     * Throws {@link IllegalArgumentException} if {@code value} is {@code null} or blank.
     *
     * @param value the string to check
     * @param field the parameter name, used in the exception message
     * @throws IllegalArgumentException if {@code value} is {@code null} or blank
     */
    public static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}