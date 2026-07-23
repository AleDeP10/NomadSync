package io.aledep10.nomadsync.exception;

/**
 * Thrown by {@code MarkerService} when a filesystem location cannot be
 * claimed, or is found to conflict with an already-claimed ancestor or
 * descendant — covers both {@code claim(...)} failures and
 * {@code checkNoNestingConflict(...)} rejections.
 *
 * <p>Deliberately generic to the marker protocol, not to any specific
 * {@code MarkerType} — callers that need a domain-specific exception (e.g.
 * {@code VaultService}, which promises {@code VaultException} to its own
 * callers) catch this at their boundary and translate it, rather than
 * letting a marker-domain exception leak into a vault-domain contract.</p>
 */
public class MarkerClaimException extends MarkerException {

    public MarkerClaimException(String message) {
        super(message);
    }

    public MarkerClaimException(String message, Throwable cause) {
        super(message, cause);
    }
}
