package io.aledep10.nomadsync.marker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for {@link VaultMarker}.
 */
@DisplayName("Unit tests for VaultMarker")
class VaultMarkerTest {

    @Test
    @DisplayName("create() sets createdAt and lastUpdate to the same value")
    void create_setsCreatedAtEqualToLastUpdate() {
        VaultMarker marker = VaultMarker.create("id-1", "Alice/vault", "/path/catalog.json", "2026-01-01T00:00:00");

        assertThat(marker.createdAt()).isEqualTo("2026-01-01T00:00:00");
        assertThat(marker.lastUpdate()).isEqualTo("2026-01-01T00:00:00");
        assertThat(marker.id()).isEqualTo("id-1");
        assertThat(marker.repoSlug()).isEqualTo("Alice/vault");
        assertThat(marker.workspacePath()).isEqualTo("/path/catalog.json");
    }

    @Test
    @DisplayName("withRefreshedTimestamp() updates only lastUpdate, preserving every other field")
    void withRefreshedTimestamp_preservesIdentityFieldsAndCreatedAt() {
        VaultMarker original = VaultMarker.create("id-1", "Alice/vault", "/path/catalog.json", "2026-01-01T00:00:00");

        VaultMarker refreshed = original.withRefreshedTimestamp("2026-01-02T00:00:00");

        assertThat(refreshed.id()).isEqualTo(original.id());
        assertThat(refreshed.repoSlug()).isEqualTo(original.repoSlug());
        assertThat(refreshed.workspacePath()).isEqualTo(original.workspacePath());
        assertThat(refreshed.createdAt()).isEqualTo(original.createdAt());
        assertThat(refreshed.lastUpdate()).isEqualTo("2026-01-02T00:00:00");
    }
}