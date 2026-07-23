package io.aledep10.nomadsync.exception;

/**
 * Thrown by a {@code MarkerTypeStrategy#deserialize} implementation when raw
 * content cannot be turned into a valid {@code Marker} of that strategy's type —
 * syntactically malformed JSON, an empty/truncated string, a missing required
 * field, or JSON shaped for a different marker type entirely.
 *
 * <p>Always carries the original parsing failure as its {@link #getCause()}
 * when one exists — preserves full diagnostic detail (e.g. Jackson's own
 * distinct messages for "unexpected end-of-input" vs. "unrecognized field")
 * instead of collapsing every failure mode into a single silent {@code null}.
 * Callers (typically {@code MarkerService}) decide what to do with it — log a
 * warning and treat it as "no marker present", or propagate further — this
 * exception only guarantees the detail is never lost before that decision is
 * made.</p>
 */
public class MarkerDeserializationException extends MarkerCodecException {

    public MarkerDeserializationException(String message) {
        super(message);
    }

    public MarkerDeserializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
