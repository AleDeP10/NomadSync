package io.aledep10.nomadsync.marker;

import io.aledep10.nomadsync.exception.MarkerDeserializationException;
import io.aledep10.nomadsync.exception.MarkerTypeMismatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkspaceMarkerStrategy}.
 */
@DisplayName("Unit tests for WorkspaceMarkerStrategy")
class WorkspaceMarkerStrategyTest {

    private final WorkspaceMarkerStrategy strategy = new WorkspaceMarkerStrategy();

    @Test
    @DisplayName("type() returns MarkerType.WORKSPACE")
    void type_returnsWorkspace() {
        assertThat(strategy.type()).isEqualTo(MarkerType.WORKSPACE);
    }

    // ── serialize() / deserialize() — round-trip ──────────────────────────────

    @Nested
    @DisplayName("round-trip")
    class RoundTripTests {

        @Test
        @DisplayName("serialize() then deserialize() round-trips all fields")
        void serialize_thenDeserialize_roundTripsAllFields() {
            WorkspaceMarker original = WorkspaceMarker.create("id-1", "Belmani", "2026-01-01T00:00:00");

            String json = strategy.serialize(original);
            Marker restored = strategy.deserialize(json);

            assertThat(restored).isEqualTo(original);
            assertThat(restored).isInstanceOf(WorkspaceMarker.class);
            WorkspaceMarker restoredWorkspace = (WorkspaceMarker) restored;
            assertThat(restoredWorkspace.workspaceName()).isEqualTo("Belmani");
            assertThat(restoredWorkspace.createdAt()).isEqualTo("2026-01-01T00:00:00");
            assertThat(restoredWorkspace.lastUpdate()).isEqualTo("2026-01-01T00:00:00");
        }
    }

    // ── serialize() — wrong marker type ────────────────────────────────────────

    @Nested
    @DisplayName("serialize() — dispatch safety")
    class SerializeWrongTypeTests {

        @Test
        @DisplayName("throws MarkerTypeMismatchException when given a VaultMarker")
        void serialize_wrongMarkerType_throwsIllegalArgumentException() {
            VaultMarker vaultMarker = VaultMarker.create("id-1", "Alice/vault",
                    "/path/catalog.json", "2026-01-01T00:00:00");

            assertThatThrownBy(() -> strategy.serialize(vaultMarker))
                    .isInstanceOf(MarkerTypeMismatchException.class)
                    .hasMessageContaining("WorkspaceMarker")
                    .hasMessageContaining("VaultMarker");
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
        @DisplayName("preserves the original Jackson parsing failure as the exception's cause")
        void deserialize_malformedJson_preservesOriginalCauseMessage() {
            assertThatThrownBy(() -> strategy.deserialize("{ not valid"))
                    .isInstanceOf(MarkerDeserializationException.class)
                    .cause().isNotNull();
        }

        @Test
        @DisplayName("throws MarkerDeserializationException, naming the field, when id is missing")
        void deserialize_missingId_throwsWithFieldNameInMessage() {
            String json = """
                    {"workspaceName":"Belmani","createdAt":"2026-01-01T00:00:00","lastUpdate":"2026-01-01T00:00:00"}""";

            assertThatThrownBy(() -> strategy.deserialize(json))
                    .isInstanceOf(MarkerDeserializationException.class)
                    .hasMessageContaining("id");
        }

        @Test
        @DisplayName("throws MarkerDeserializationException, naming the field, when workspaceName is missing")
        void deserialize_missingWorkspaceName_throwsWithFieldNameInMessage() {
            String json = """
                    {"id":"id-1","createdAt":"2026-01-01T00:00:00","lastUpdate":"2026-01-01T00:00:00"}""";

            assertThatThrownBy(() -> strategy.deserialize(json))
                    .isInstanceOf(MarkerDeserializationException.class)
                    .hasMessageContaining("workspaceName");
        }

        @Test
        @DisplayName("throws MarkerDeserializationException when the JSON is syntactically valid "
                + "but shaped for a different marker type (e.g. a VaultMarker's own fields)")
        void deserialize_validJsonWrongShape_throwsMarkerDeserializationException() {
            String vaultShapedJson = """
                    {"id":"id-1","repoSlug":"Alice/vault","catalogPath":"/path/catalog.json","createdAt":"2026-01-01T00:00:00","lastUpdate":"2026-01-01T00:00:00"}""";

            assertThatThrownBy(() -> strategy.deserialize(vaultShapedJson))
                    .isInstanceOf(MarkerDeserializationException.class)
                    .hasMessageContaining("workspaceName");
        }
    }

    // ── describeConflict() ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("describeConflict()")
    class DescribeConflictTests {

        @Test
        @DisplayName("returns a message containing the existing marker's workspaceName")
        void describeConflict_returnsMessageContainingWorkspaceName() {
            WorkspaceMarker existing = WorkspaceMarker.create("id-1", "ForeignWorkspace", "2026-01-01T00:00:00");

            String message = strategy.describeConflict(existing);

            assertThat(message).contains("ForeignWorkspace");
        }

        @Test
        @DisplayName("throws MarkerTypeMismatchException when given a VaultMarker")
        void describeConflict_wrongMarkerType_throwsMarkerTypeMismatchException() {
            VaultMarker vaultMarker = VaultMarker.create("id-1", "Alice/vault",
                    "/path/catalog.json", "2026-01-01T00:00:00");

            assertThatThrownBy(() -> strategy.describeConflict(vaultMarker))
                    .isInstanceOf(MarkerTypeMismatchException.class)
                    .hasMessageContaining("WorkspaceMarker")
                    .hasMessageContaining("VaultMarker");
        }
    }

    // ── sameClaimant() — inherited default, sanity check only ─────────────────

    @Nested
    @DisplayName("sameClaimant() — inherited default from MarkerTypeStrategy")
    class SameClaimantTests {

        @Test
        @DisplayName("matching id returns true")
        void sameClaimant_matchingId_returnsTrue() {
            WorkspaceMarker existing = WorkspaceMarker.create("id-1", "Belmani", "2026-01-01T00:00:00");

            assertThat(strategy.sameClaimant(existing, "id-1")).isTrue();
        }

        @Test
        @DisplayName("different id returns false")
        void sameClaimant_differentId_returnsFalse() {
            WorkspaceMarker existing = WorkspaceMarker.create("id-1", "Belmani", "2026-01-01T00:00:00");

            assertThat(strategy.sameClaimant(existing, "id-2")).isFalse();
        }
    }
}
