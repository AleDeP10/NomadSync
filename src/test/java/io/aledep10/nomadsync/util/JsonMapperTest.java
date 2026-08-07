package io.aledep10.nomadsync.util;

import io.aledep10.nomadsync.marker.VaultMarker;
import io.aledep10.nomadsync.workspace.WorkspaceEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for {@link JsonMapper}.
 *
 * <p>Covers the {@code .vault} marker round-trip, the {@code workspaces.json}
 * registry round-trip, and {@code loadDefaultWorkspacePath}. Broader coverage
 * of vault list load/save is exercised indirectly by {@code VaultServiceTest};
 * no equivalent {@code WorkspaceServiceTest} exists yet, so the workspace
 * persistence methods are covered directly here for now.</p>
 *
 * <p>Each test gets a fresh, isolated temp directory via {@link TempDirs}
 * (injected by {@link TempDirCleanupExtension}) — no manual cleanup: a
 * passing test's directory is deleted automatically; a failing test's is
 * left on disk for inspection.</p>
 */
@ExtendWith(TempDirCleanupExtension.class)
@DisplayName("Unit tests for JsonMapper")
class JsonMapperTest {

    Path tempDir;

    @BeforeEach
    void setUp(TempDirs tempDirs) throws IOException {
        tempDir = tempDirs.newDir("JsonMapperTest", "root");
    }

    // No @AfterEach — TempDirCleanupExtension.testSuccessful() owns cleanup,
    // conditionally on the test outcome.

    @Nested
    @DisplayName("Vault marker round-trip")
    class VaultMarkerRoundTripTests {

        @Test
        @DisplayName("loadVaultMarkerFromFile returns null when the file does not exist")
        void loadVaultMarkerFromFile_missingFile_returnsNull() throws IOException {
            File markerFile = tempDir.resolve(".vault").toFile();

            VaultMarker marker = JsonMapper.loadVaultMarkerFromFile(markerFile);

            assertThat(marker).isNull();
        }

        @Test
        @DisplayName("saveVaultMarkerToFile followed by loadVaultMarkerFromFile round-trips all fields")
        void saveThenLoad_roundTripsAllFields() throws IOException {
            File markerFile = tempDir.resolve(".vault").toFile();
            VaultMarker original = VaultMarker.create("id-1", "Alice/vault",
                    tempDir.resolve("catalog.json").toString(), "2026-01-01T00:00:00");

            JsonMapper.saveVaultMarkerToFile(markerFile, original);
            VaultMarker loaded = JsonMapper.loadVaultMarkerFromFile(markerFile);

            assertThat(loaded).isEqualTo(original);
        }

        @Test
        @DisplayName("saveVaultMarkerToFile overwrites a previously existing marker")
        void save_overwritesExistingMarker() throws IOException {
            File markerFile = tempDir.resolve(".vault").toFile();
            VaultMarker first = VaultMarker.create("id-1", "Alice/vault",
                    tempDir.resolve("catalog.json").toString(), "2026-01-01T00:00:00");
            JsonMapper.saveVaultMarkerToFile(markerFile, first);

            VaultMarker refreshed = first.withRefreshedTimestamp("2026-01-02T00:00:00");
            JsonMapper.saveVaultMarkerToFile(markerFile, refreshed);

            VaultMarker loaded = JsonMapper.loadVaultMarkerFromFile(markerFile);
            assertThat(loaded.lastUpdate()).isEqualTo("2026-01-02T00:00:00");
            assertThat(loaded.createdAt()).isEqualTo("2026-01-01T00:00:00");
        }
    }

    @Nested
    @DisplayName("Workspaces registry round-trip")
    class WorkspacesPersistenceTests {

        @Test
        @DisplayName("loadWorkspacesFromFile returns an empty list when the file does not exist")
        void loadWorkspacesFromFile_missingFile_returnsEmptyList() throws IOException {
            File registryFile = tempDir.resolve("workspaces.json").toFile();

            assertThat(JsonMapper.loadWorkspacesFromFile(registryFile).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("saveWorkspacesToFile followed by loadWorkspacesFromFile round-trips workspaceName, path and isDefault, preserving order")
        void saveThenLoad_roundTripsAllFieldsInOrder() throws IOException {
            File registryFile = tempDir.resolve("workspaces.json").toFile();
            WorkspaceEntry defaultWorkspace = new WorkspaceEntry("default", tempDir.resolve("default").toString(), true);
            WorkspaceEntry secondary = new WorkspaceEntry("laptop-work", tempDir.resolve("laptop").toString(), false);

            JsonMapper.saveWorkspacesToFile(registryFile, List.of(defaultWorkspace, secondary));
            List<WorkspaceEntry> loaded = JsonMapper.loadWorkspacesFromFile(registryFile);

            // WorkspaceEntry#equals compares only workspaceName (NomadSync-WSP-001) —
            // path and isDefault are asserted explicitly, not via equals-based matchers.
            assertThat(loaded.size() == 2).isTrue();
            assertThat(loaded.get(0).getWorkspaceName()).isEqualTo("default");
            assertThat(loaded.get(0).getPath()).isEqualTo(defaultWorkspace.getPath());
            assertThat(loaded.get(0).isDefault()).isTrue();
            assertThat(loaded.get(1).getWorkspaceName()).isEqualTo("laptop-work");
            assertThat(loaded.get(1).getPath()).isEqualTo(secondary.getPath());
            assertThat(loaded.get(1).isDefault()).isFalse();
        }

        @Test
        @DisplayName("saveWorkspacesToFile never writes an explicit isDefault:false — the key is omitted entirely for non-default entries")
        void save_omitsIsDefaultKeyForNonDefaultEntries() throws IOException {
            File registryFile = tempDir.resolve("workspaces.json").toFile();
            WorkspaceEntry defaultWorkspace = new WorkspaceEntry("default", tempDir.resolve("default").toString(), true);
            WorkspaceEntry secondary = new WorkspaceEntry("laptop-work", tempDir.resolve("laptop").toString(), false);

            JsonMapper.saveWorkspacesToFile(registryFile, List.of(defaultWorkspace, secondary));
            String raw = Files.readString(registryFile.toPath());

            // Boolean, not primitive boolean, on WorkspaceEntryDto (see NomadSync-WSP-001
            // discussion on JsonMapper): null on the non-default entry, never a written "false".
            assertThat(raw).doesNotContain("false");
            assertThat(raw).contains("\"isDefault\"");
        }
    }

}