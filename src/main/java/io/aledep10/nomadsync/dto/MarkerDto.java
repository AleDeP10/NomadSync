package io.aledep10.nomadsync.dto;

import io.aledep10.nomadsync.marker.Marker;

/**
 * Shared contract for every Jackson DTO backing a {@link Marker} descriptor —
 * lets {@code AbstractMarkerTypeStrategy} call {@code toDomain()} polymorphically
 * without needing a per-type conversion hook for that direction.
 *
 * <p>The opposite direction ({@code fromDomain(D)}) stays a {@code static}
 * factory method on each concrete DTO, as today — static factory methods
 * cannot be part of an interface contract in a genuinely polymorphic way, so
 * that conversion remains a small protected hook
 * ({@code AbstractMarkerTypeStrategy#toDto}) in each concrete strategy instead.</p>
 *
 * @param <D> the concrete {@link Marker} subtype this DTO deserialises to
 */
public interface MarkerDto<D extends Marker> {
    D toDomain();
}
