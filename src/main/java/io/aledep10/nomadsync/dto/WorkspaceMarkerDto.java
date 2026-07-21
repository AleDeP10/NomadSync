package io.aledep10.nomadsync.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aledep10.nomadsync.marker.WorkspaceMarker;

/**
 * Jackson DTO for a {@link WorkspaceMarker} descriptor file.
 */
public record WorkspaceMarkerDto(String id, String workspaceName, String createdAt, String lastUpdate)
        implements MarkerDto<WorkspaceMarker> {

    @JsonCreator
    public WorkspaceMarkerDto(
            @JsonProperty("id") String id,
            @JsonProperty("workspaceName") String workspaceName,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("lastUpdate") String lastUpdate) {
        this.id = id;
        this.workspaceName = workspaceName;
        this.createdAt = createdAt;
        this.lastUpdate = lastUpdate;
    }

    @Override
    public WorkspaceMarker toDomain() {
        return WorkspaceMarker.create(id, workspaceName, createdAt)
                .withRefreshedTimestamp(lastUpdate);
    }

    public static WorkspaceMarkerDto fromDomain(WorkspaceMarker marker) {
        return new WorkspaceMarkerDto(marker.id(), marker.workspaceName(),
                marker.createdAt(), marker.lastUpdate());
    }
}
