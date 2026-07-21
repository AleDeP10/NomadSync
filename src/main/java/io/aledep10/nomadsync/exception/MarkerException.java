package io.aledep10.nomadsync.exception;

/**
 * Root of the marker protocol's exception domain — {@link MarkerCodecException}
 * (serialization/deserialization failures) and {@link MarkerTypeMismatchException}
 * (dispatch on the wrong concrete {@code Marker} type) are its two branches.
 *
 * <p>Mirrors the existing {@code VaultException} family shape (one domain root,
 * flat siblings per failure category) — lets a caller catch any marker-related
 * failure uniformly via this root, or a specific category/exception when it
 * needs to react differently.</p>
 */
public abstract class MarkerException extends RuntimeException {

    protected MarkerException(String message) {
        super(message);
    }

    protected MarkerException(String message, Throwable cause) {
        super(message, cause);
    }
}
