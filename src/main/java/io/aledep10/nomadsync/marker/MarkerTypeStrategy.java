package io.aledep10.nomadsync.marker;

import io.aledep10.nomadsync.exception.MarkerDeserializationException;
import io.aledep10.nomadsync.exception.MarkerSerializationException;
import io.aledep10.nomadsync.exception.MarkerTypeMismatchException;

/**
 * Type-specific behaviour for a single {@link MarkerType} — everything
 * {@code MarkerService} needs to delegate to, without ever knowing the
 * concrete shape of that type's {@link Marker} subclass.
 *
 * <p>One implementation per active {@code MarkerType}, held by
 * {@code MarkerService} in a {@code Map<MarkerType, MarkerTypeStrategy>}.
 * Adding a new active marker type means writing a new implementation here —
 * {@code MarkerService} itself never changes.</p>
 *
 * <p>Deliberately excludes anything that is either pure generic mechanics
 * (filesystem claim/release/scan — {@code MarkerService}'s own job) or
 * genuinely type-specific beyond what any other type would need (e.g.
 * {@code WorkspaceMarkerStrategy}'s future {@code withMergedConfig}/
 * {@code withMergedCatalog} — not part of this contract, since no other
 * type would ever implement them meaningfully).</p>
 */
public interface MarkerTypeStrategy {

    /**
     * Which {@link MarkerType} this strategy handles. Used by
     * {@code MarkerService} to select the right strategy from its map.
     */
    MarkerType type();

    /**
     * Serialises a marker of this strategy's type to its on-disk JSON form.
     *
     * @param marker a {@link Marker} instance of this strategy's own concrete
     *               type — never a different type's marker
     * @return the JSON representation to write to {@code descriptor.json}
     * @throws MarkerTypeMismatchException if {@code marker} is not an instance
     *                                      of this strategy's own concrete type —
     *                                      a dispatch bug in the caller, must fail
     *                                      loudly at the point of misuse, not as a
     *                                      confusing field-access failure downstream
     * @throws MarkerSerializationException if the marker cannot be serialised —
     *                                       expected to be effectively unreachable
     *                                       for today's flat, string-only DTOs, but
     *                                       required by the checked Jackson API
     */
    String serialize(Marker marker) throws MarkerSerializationException;

    /**
     * Deserialises a marker of this strategy's type from its on-disk JSON form.
     *
     * @param raw the raw file content read from {@code descriptor.json}
     * @return the reconstructed {@link Marker} — never {@code null}
     * @throws MarkerDeserializationException if {@code raw} is empty, syntactically
     *          malformed, missing a required field, or shaped for a different
     *          marker type — always carries the original parsing failure as its
     *          cause when one exists, so the caller never loses diagnostic detail
     */
    Marker deserialize(String raw) throws MarkerDeserializationException;

    /**
     * Whether an already-existing marker on disk belongs to the same claimant
     * as the one currently being confirmed or reclaiming — the sole criterion
     * for "is this my own marker, or does it belong to someone else".
     *
     * <p>Default implementation compares only {@link Marker#id()} — true for
     * every marker type today, since ownership is always identity-based
     * regardless of what type-specific fields a marker carries. Override only
     * if a future type genuinely needs a different notion of "same claimant".</p>
     *
     * @param existing     the marker found on disk
     * @param candidateId  the {@code id} of the entity currently claiming/confirming
     * @return {@code true} if {@code existing} was written by the same claimant
     */
    default boolean sameClaimant(Marker existing, String candidateId) {
        return existing.id().equals(candidateId);
    }

    /**
     * Produces a human-readable description of an existing marker, for use in
     * conflict messages (e.g. {@code "already claimed by vault 'Alice/portfolio'"}).
     *
     * @param existing the marker already present at a claimed or conflicting location
     * @return a description suitable for direct inclusion in a {@code VaultException} message
     * @throws MarkerTypeMismatchException if {@code existing} is not an instance of
     *                                      this strategy's own concrete type
     */
    String describeConflict(Marker existing);
}
