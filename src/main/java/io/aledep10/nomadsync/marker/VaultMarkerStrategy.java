package io.aledep10.nomadsync.marker;

import io.aledep10.nomadsync.dto.VaultMarkerDto;

/**
 * {@link MarkerTypeStrategy} implementation for {@link MarkerType#VAULT}.
 *
 * <p>All mechanics (dispatch safety, JSON round-trip, required-field validation)
 * live in {@link AbstractMarkerTypeStrategy} — this class supplies only what is
 * genuinely specific to vaults: the DTO conversion and the conflict message.</p>
 */
public final class VaultMarkerStrategy extends AbstractMarkerTypeStrategy<VaultMarker, VaultMarkerDto> {

    public VaultMarkerStrategy() {
        super(MarkerType.VAULT, VaultMarker.class, VaultMarkerDto.class, "id", "repoSlug", "catalogPath");
    }

    @Override
    protected VaultMarkerDto toDto(VaultMarker domain) {
        return VaultMarkerDto.fromDomain(domain);
    }

    @Override
    protected String conflictMessage(VaultMarker domain) {
        return "already claimed by vault '" + domain.repoSlug() + "'";
    }
}
