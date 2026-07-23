package io.aledep10.nomadsync.exception;

/**
 * Common ancestor for failures converting a {@code Marker} to/from its on-disk
 * JSON form — {@link MarkerSerializationException} (domain → JSON) and
 * {@link MarkerDeserializationException} (JSON → domain).
 *
 * <p>Branch of {@link MarkerException} dedicated to codec failures — sibling
 * of {@link MarkerTypeMismatchException}, which covers dispatch errors instead.</p>
 */
public abstract class MarkerCodecException extends MarkerException {

    protected MarkerCodecException(String message) {
        super(message);
    }

    protected MarkerCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
