package io.aledep10.nomadsync.marker;

import io.aledep10.nomadsync.exception.MarkerDeserializationException;
import io.aledep10.nomadsync.exception.MarkerTypeMismatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link VaultMarkerStrategy}.
 */
@DisplayName("Unit tests for VaultMarkerStrategy")
class VaultMarkerStrategyTest {

    private final VaultMarkerStrategy strategy = new VaultMarkerStrategy();

    @Test
    @DisplayName("type() returns MarkerType.VAULT")
    void type_returnsVault() {
        assertThat(strategy.type()).isEqualTo(MarkerType.VAULT);
    }

    // ── serialize() / deserialize() — round-trip ──────────────────────────────

    @Nested
    @DisplayName("round-trip")
    class RoundTripTests {

        @Test
        @DisplayName("serialize() then deserialize() round-trips all fields")
        void serialize_thenDeserialize_roundTripsAllFields() {
            VaultMarker original = VaultMarker.create("id-1", "Alice/vault",
                    "/path/catalog.json", "2026-01-01T00:00:00");

            String json = strategy.serialize(original);
            Marker restored = strategy.deserialize(json);

            assertThat(restored).isEqualTo(original);
            assertThat(restored).isInstanceOf(VaultMarker.class);
            VaultMarker restoredVault = (VaultMarker) restored;
            assertThat(restoredVault.repoSlug()).isEqualTo("Alice/vault");
            assertThat(restoredVault.workspacePath()).isEqualTo("/path/catalog.json");
            assertThat(restoredVault.createdAt()).isEqualTo("2026-01-01T00:00:00");
            assertThat(restoredVault.lastUpdate()).isEqualTo("2026-01-01T00:00:00");
        }
    }

    // ── serialize() — wrong marker type ────────────────────────────────────────

    @Nested
    @DisplayName("serialize() — dispatch safety")
    class SerializeWrongTypeTests {

        @Test
        @DisplayName("throws MarkerTypeMismatchException when given a WorkspaceMarker")
        void serialize_wrongMarkerType_throwsIllegalArgumentException() {
            WorkspaceMarker workspaceMarker = WorkspaceMarker.create("id-1", "Alice", "2026-01-01T00:00:00");

            assertThatThrownBy(() -> strategy.serialize(workspaceMarker))
                    .isInstanceOf(MarkerTypeMismatchException.class)
                    .hasMessageContaining("VaultMarker")
                    .hasMessageContaining("WorkspaceMarker");
        }
    }

    // ── deserialize() — malformed or incomplete input ─────────────────────────

    @Nested
    @DisplayName("deserialize() — malformed or incomplete input")
    class DeserializeFailureTests {

        @Test
        @DisplayName("throws MarkerDeserializationException on syntactically malformed JSON")
        void deserialize_malformedJson_throwsMarkerDeserializationException() {
            assertThatThrownBy(() -> strategy.deserialize("{ this is not valid JSON"))
                    .isInstanceOf(MarkerDeserializationException.class);
        }

        @Test
        @DisplayName("throws MarkerDeserializationException on an empty string")
        void deserialize_emptyString_throwsMarkerDeserializationException() {
            assertThatThrownBy(() -> strategy.deserialize(""))
                    .isInstanceOf(MarkerDeserializationException.class);
        }

        @Test
        @DisplayName("preserves the original Jackson parsing failure as the exception's cause "
                + "— the whole point of moving away from a silent null")
        void deserialize_malformedJson_preservesOriginalCauseMessage() {
            assertThatThrownBy(() -> strategy.deserialize("{ not valid"))
                    .isInstanceOf(MarkerDeserializationException.class)
                    .cause().isNotNull();
        }

        @Test
        @DisplayName("throws MarkerDeserializationException, naming the field, when repoSlug is missing")
        void deserialize_missingRepoSlug_throwsWithFieldNameInMessage() {
            String json = """
                    {"id":"id-1","workspacePath":"/path/catalog.json","createdAt":"2026-01-01T00:00:00","lastUpdate":"2026-01-01T00:00:00"}""";

            assertThatThrownBy(() -> strategy.deserialize(json))
                    .isInstanceOf(MarkerDeserializationException.class)
                    .hasMessageContaining("repoSlug");
        }

        @Test
        @DisplayName("throws MarkerDeserializationException, naming the field, when workspacePath is missing")
        void deserialize_missingworkspacePath_throwsWithFieldNameInMessage() {
            String json = """
                    {"id":"id-1","repoSlug":"Alice/vault","createdAt":"2026-01-01T00:00:00","lastUpdate":"2026-01-01T00:00:00"}""";

            assertThatThrownBy(() -> strategy.deserialize(json))
                    .isInstanceOf(MarkerDeserializationException.class)
                    .hasMessageContaining("workspacePath");
        }

        @Test
        @DisplayName("throws MarkerDeserializationException, naming the field, when id is missing")
        void deserialize_missingId_throwsWithFieldNameInMessage() {
            String json = """
                    {"repoSlug":"Alice/vault","workspacePath":"/path/catalog.json","createdAt":"2026-01-01T00:00:00","lastUpdate":"2026-01-01T00:00:00"}""";

            assertThatThrownBy(() -> strategy.deserialize(json))
                    .isInstanceOf(MarkerDeserializationException.class)
                    .hasMessageContaining("id");
        }

        @Test
        @DisplayName("throws MarkerDeserializationException when the JSON is syntactically valid "
                + "but shaped for a different marker type (e.g. a WorkspaceMarker's own fields)")
        void deserialize_validJsonWrongShape_throwsMarkerDeserializationException() {
            String workspaceShapedJson = """
                    {"id":"id-1","workspaceName":"Alice","createdAt":"2026-01-01T00:00:00","lastUpdate":"2026-01-01T00:00:00"}""";

            assertThatThrownBy(() -> strategy.deserialize(workspaceShapedJson))
                    .isInstanceOf(MarkerDeserializationException.class);
        }
    }

    // ── describeConflict() ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("describeConflict()")
    class DescribeConflictTests {

        @Test
        @DisplayName("returns a message containing the existing marker's repoSlug")
        void describeConflict_returnsMessageContainingRepoSlug() {
            VaultMarker existing = VaultMarker.create("id-1", "Bob/foreign-vault",
                    "/some/catalog.json", "2026-01-01T00:00:00");

            String message = strategy.describeConflict(existing);

            assertThat(message).contains("Bob/foreign-vault");
        }

        @Test
        @DisplayName("throws MarkerTypeMismatchException when given a WorkspaceMarker")
        void describeConflict_wrongMarkerType_throwsMarkerTypeMismatchException() {
            WorkspaceMarker workspaceMarker = WorkspaceMarker.create("id-1", "Alice", "2026-01-01T00:00:00");

            assertThatThrownBy(() -> strategy.describeConflict(workspaceMarker))
                    .isInstanceOf(MarkerTypeMismatchException.class)
                    .hasMessageContaining("VaultMarker")
                    .hasMessageContaining("WorkspaceMarker");
        }
    }

    // ── sameClaimant() — inherited default, sanity check only ─────────────────

    @Nested
    @DisplayName("sameClaimant() — inherited default from MarkerTypeStrategy")
    class SameClaimantTests {

        @Test
        @DisplayName("matching id returns true")
        void sameClaimant_matchingId_returnsTrue() {
            VaultMarker existing = VaultMarker.create("id-1", "Alice/vault",
                    "/path/catalog.json", "2026-01-01T00:00:00");

            assertThat(strategy.sameClaimant(existing, "id-1")).isTrue();
        }

        @Test
        @DisplayName("different id returns false")
        void sameClaimant_differentId_returnsFalse() {
            VaultMarker existing = VaultMarker.create("id-1", "Alice/vault",
                    "/path/catalog.json", "2026-01-01T00:00:00");

            assertThat(strategy.sameClaimant(existing, "id-2")).isFalse();
        }
    }
}
