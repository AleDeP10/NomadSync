package io.aledep10.nomadsync.marker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for {@link WorkspaceMarker}.
 */
@DisplayName("Unit tests for WorkspaceMarker")
class WorkspaceMarkerTest {

    @Test
    @DisplayName("create() sets createdAt and lastUpdate to the same value")
    void create_setsCreatedAtEqualToLastUpdate() {
        WorkspaceMarker marker = WorkspaceMarker.create("id-1", "Belmani", "2026-01-01T00:00:00");

        assertThat(marker.createdAt()).isEqualTo("2026-01-01T00:00:00");
        assertThat(marker.lastUpdate()).isEqualTo("2026-01-01T00:00:00");
        assertThat(marker.id()).isEqualTo("id-1");
        assertThat(marker.workspaceName()).isEqualTo("Belmani");
    }

    @Test
    @DisplayName("withRefreshedTimestamp() updates only lastUpdate, preserving every other field")
    void withRefreshedTimestamp_preservesIdentityFieldsAndCreatedAt() {
        WorkspaceMarker original = WorkspaceMarker.create("id-1", "Belmani", "2026-01-01T00:00:00");

        WorkspaceMarker refreshed = original.withRefreshedTimestamp("2026-01-02T00:00:00");

        assertThat(refreshed.id()).isEqualTo(original.id());
        assertThat(refreshed.workspaceName()).isEqualTo(original.workspaceName());
        assertThat(refreshed.createdAt()).isEqualTo(original.createdAt());
        assertThat(refreshed.lastUpdate()).isEqualTo("2026-01-02T00:00:00");
    }
}
