package io.aledep10.nomadsync.exception;

/**
 * Thrown when a {@code MarkerTypeStrategy} method receives a {@code Marker}
 * that is not an instance of its own concrete type — e.g.
 * {@code VaultMarkerStrategy.serialize(workspaceMarker)}.
 *
 * <p>Always a dispatch bug in the caller (typically {@code MarkerService}),
 * never a data problem — no underlying cause to wrap, unlike
 * {@link MarkerCodecException}'s branches, which always originate from a
 * caught Jackson failure. Deliberately its own sibling of
 * {@link MarkerCodecException} under {@link MarkerException}, rather than a
 * generic {@link IllegalArgumentException} — a generic JDK type carries no
 * NomadSync-specific meaning and would be indistinguishable from an unrelated
 * {@code IllegalArgumentException} raised by some other dependency in the
 * same call, if a caller ever needed to catch this specifically.</p>
 */
public class MarkerTypeMismatchException extends MarkerException {

    public MarkerTypeMismatchException(String message) {
        super(message);
    }
}
