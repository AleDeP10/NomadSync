package io.aledep10.nomadsync.exception;

/**
 * Thrown by a {@code MarkerTypeStrategy#serialize} implementation when a
 * {@code Marker} cannot be converted to its on-disk JSON form.
 *
 * <p>In practice this should be effectively unreachable — every current
 * marker DTO is a flat record of plain {@code String} fields, which Jackson
 * cannot fail to serialise. Still required by the checked
 * {@code JsonProcessingException} the underlying {@code ObjectMapper} call
 * declares — wrapped here as unchecked, consistent with the rest of the
 * marker package never propagating Jackson's checked exceptions directly.</p>
 */
public class MarkerSerializationException extends MarkerCodecException {

    public MarkerSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
