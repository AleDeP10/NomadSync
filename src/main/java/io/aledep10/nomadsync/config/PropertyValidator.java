package io.aledep10.nomadsync.config;

import java.util.Optional;

/**
 * A single, reusable check on a property's value — independent of where the
 * value came from (a {@code config.properties} file, a {@code Vault}'s own
 * typed field, or a {@code --git.*} CLI flag about to be applied to either).
 * Same validator, same key, regardless of the destination — a malformed
 * {@code git.email} is malformed whether it's about to land in
 * {@code installConfig.properties} or on a single vault's own field.
 */
public interface PropertyValidator {

    /**
     * @param value the value to check — never blank (blank-ness is checked
     *              upstream, by {@code hasBlankRequiredFlags}/equivalent, not
     *              duplicated here)
     * @return empty if valid, otherwise a human-readable reason
     */
    Optional<String> validate(String value);
}