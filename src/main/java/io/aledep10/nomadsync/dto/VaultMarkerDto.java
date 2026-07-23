package io.aledep10.nomadsync.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aledep10.nomadsync.marker.VaultMarker;

/**
 * Jackson DTO for a {@link VaultMarker} descriptor file.
 */
public record VaultMarkerDto(String id, String repoSlug, String catalogPath, String createdAt, String lastUpdate)
        implements MarkerDto<VaultMarker> {

    @JsonCreator
    public VaultMarkerDto(
            @JsonProperty("id") String id,
            @JsonProperty("repoSlug") String repoSlug,
            @JsonProperty("catalogPath") String catalogPath,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("lastUpdate") String lastUpdate) {
        this.id = id;
        this.repoSlug = repoSlug;
        this.catalogPath = catalogPath;
        this.createdAt = createdAt;
        this.lastUpdate = lastUpdate;
    }

    @Override
    public VaultMarker toDomain() {
        return VaultMarker.create(id, repoSlug, catalogPath, createdAt)
                .withRefreshedTimestamp(lastUpdate);
    }

    public static VaultMarkerDto fromDomain(VaultMarker marker) {
        return new VaultMarkerDto(marker.id(), marker.repoSlug(), marker.catalogPath(),
                marker.createdAt(), marker.lastUpdate());
    }
}
