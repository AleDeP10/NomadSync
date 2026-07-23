package io.aledep10.nomadsync.marker;

import io.aledep10.nomadsync.dto.WorkspaceMarkerDto;

/**
 * {@link MarkerTypeStrategy} implementation for {@link MarkerType#WORKSPACE}.
 *
 * <p>All mechanics (dispatch safety, JSON round-trip, required-field validation)
 * live in {@link AbstractMarkerTypeStrategy} — this class supplies only what is
 * genuinely specific to workspaces: the DTO conversion and the conflict message.</p>
 */
public final class WorkspaceMarkerStrategy extends AbstractMarkerTypeStrategy<WorkspaceMarker, WorkspaceMarkerDto> {

    public WorkspaceMarkerStrategy() {
        super(MarkerType.WORKSPACE, WorkspaceMarker.class, WorkspaceMarkerDto.class, "id", "workspaceName");
    }

    @Override
    protected WorkspaceMarkerDto toDto(WorkspaceMarker domain) {
        return WorkspaceMarkerDto.fromDomain(domain);
    }

    @Override
    protected String conflictMessage(WorkspaceMarker domain) {
        return "already claimed by workspace '" + domain.workspaceName() + "'";
    }
}
