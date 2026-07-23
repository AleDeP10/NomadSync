package io.aledep10.nomadsync.marker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aledep10.nomadsync.dto.MarkerDto;
import io.aledep10.nomadsync.exception.MarkerDeserializationException;
import io.aledep10.nomadsync.exception.MarkerSerializationException;
import io.aledep10.nomadsync.exception.MarkerTypeMismatchException;
import io.aledep10.nomadsync.util.StringUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Shared skeleton for every {@link MarkerTypeStrategy} implementation.
 *
 * <p>Handles everything that is mechanically identical across marker types —
 * dispatch safety ({@link MarkerTypeMismatchException}), the JSON round-trip via
 * a shared {@link ObjectMapper}, and required-field validation via reflection on
 * the DTO's record accessors (collecting <em>every</em> missing field into one
 * message, never just the first found) — leaving only genuinely type-specific
 * behaviour to each subclass, as two small protected hooks.</p>
 *
 * <p>{@code Class<D>}/{@code Class<T>} are supplied explicitly by each
 * subclass's constructor rather than recovered reflectively from the generic
 * signature — type erasure makes the latter needlessly fragile when the
 * concrete types are already known at the point each subclass is written.</p>
 *
 * @param <D> the concrete {@link Marker} subtype this strategy handles
 * @param <T> the Jackson DTO record type for {@code D}
 */
public abstract class AbstractMarkerTypeStrategy<D extends Marker, T extends MarkerDto<D>>
        implements MarkerTypeStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MarkerType markerType;
    private final Class<D> domainClass;
    private final Class<T> dtoClass;
    private final List<String> requiredFieldNames;

    /**
     * @param markerType         the {@link MarkerType} this strategy handles
     * @param domainClass        the concrete {@link Marker} subtype {@code D}
     * @param dtoClass           the Jackson DTO record type {@code T} for {@code D}
     * @param requiredFieldNames names of {@code T}'s record accessor methods that
     *                           must all be non-blank for a successfully-parsed DTO
     *                           to count as a valid marker — each name is resolved
     *                           via reflection, so it must match an actual accessor
     *                           on {@code T} exactly (a mismatch is a wiring bug,
     *                           surfaced as {@link IllegalStateException}, never as
     *                           {@link MarkerDeserializationException} — that one is
     *                           reserved for genuine data problems, not code bugs)
     */
    protected AbstractMarkerTypeStrategy(MarkerType markerType, Class<D> domainClass,
                                          Class<T> dtoClass, String... requiredFieldNames) {
        this.markerType = markerType;
        this.domainClass = domainClass;
        this.dtoClass = dtoClass;
        this.requiredFieldNames = List.of(requiredFieldNames);
    }

    /**
     * Converts an already dispatch-checked domain marker to its DTO form, for
     * serialisation — typically a one-line call to {@code T.fromDomain(domain)}.
     */
    protected abstract T toDto(D domain);

    /**
     * Builds the human-readable conflict description for this type, e.g.
     * {@code "already claimed by vault 'Alice/portfolio'"}.
     */
    protected abstract String conflictMessage(D domain);

    @Override
    public final MarkerType type() {
        return markerType;
    }

    @Override
    public final String serialize(Marker marker) {
        D domain = castOrThrow(marker);
        try {
            return MAPPER.writeValueAsString(toDto(domain));
        } catch (JsonProcessingException e) {
            throw new MarkerSerializationException(
                    "Unable to serialize " + domainClass.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public final Marker deserialize(String raw) throws MarkerDeserializationException {
        T dto;
        try {
            dto = MAPPER.readValue(raw, dtoClass);
        } catch (JsonProcessingException e) {
            throw new MarkerDeserializationException(
                    "Unable to deserialize " + domainClass.getSimpleName() + ": " + e.getMessage(), e);
        }

        List<String> missingFields = requiredFieldNames.stream()
                .filter(field -> StringUtil.isBlank(fieldValue(dto, field)))
                .toList();

        if (!missingFields.isEmpty()) {
            throw new MarkerDeserializationException(
                    "Missing required fields: " + String.join(", ", missingFields));
        }
        return dto.toDomain();
    }

    @Override
    public final String describeConflict(Marker existing) {
        return conflictMessage(castOrThrow(existing));
    }

    private D castOrThrow(Marker marker) {
        if (!domainClass.isInstance(marker)) {
            throw new MarkerTypeMismatchException(
                    "expected " + domainClass.getSimpleName() + ", got " + marker.getClass().getSimpleName());
        }
        return domainClass.cast(marker);
    }

    /**
     * Reads the named record accessor's value off {@code dto} via reflection.
     *
     * @throws IllegalStateException if {@code fieldName} does not match an actual
     *          accessor on {@code T} — a constructor wiring bug in the subclass,
     *          never a data problem, so never {@link MarkerDeserializationException}
     */
    private String fieldValue(T dto, String fieldName) {
        try {
            Method accessor = dtoClass.getMethod(fieldName);
            Object value = accessor.invoke(dto);
            return value == null ? null : value.toString();
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(
                    "Required field '" + fieldName + "' does not exist as an accessor on "
                            + dtoClass.getSimpleName()
                            + " — check the required field names passed to the constructor", e);
        }
    }
}
