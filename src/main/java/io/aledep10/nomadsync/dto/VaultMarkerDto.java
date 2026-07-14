package io.aledep10.nomadsync.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aledep10.nomadsync.vault.VaultMarker;

/**
 * Jackson DTO for the {@code .vault} marker file.
 */
public record VaultMarkerDto(String id, String repoSlug, String jsonPath, String createdAt, String lastUpdate) {

    @JsonCreator
    public VaultMarkerDto(
            @JsonProperty("id") String id,
            @JsonProperty("repoSlug") String repoSlug,
            @JsonProperty("jsonPath") String jsonPath,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("lastUpdate") String lastUpdate) {
        this.id = id;
        this.repoSlug = repoSlug;
        this.jsonPath = jsonPath;
        this.createdAt = createdAt;
        this.lastUpdate = lastUpdate;
    }

    public VaultMarker toDomain() {
        return new VaultMarker(id, repoSlug, jsonPath, createdAt, lastUpdate);
    }

    public static VaultMarkerDto fromDomain(VaultMarker marker) {
        return new VaultMarkerDto(marker.id(), marker.repoSlug(), marker.jsonPath(),
                marker.createdAt(), marker.lastUpdate());
    }
}