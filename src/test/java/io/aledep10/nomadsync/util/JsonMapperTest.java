package io.aledep10.nomadsync.util;

import io.aledep10.nomadsync.marker.VaultMarker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for {@link JsonMapper} — scoped to the {@code .vault} marker
 * round-trip only. Broader coverage of vault list load/save is exercised
 * indirectly by {@code VaultServiceTest}.
 *
 * <p>Each test gets a fresh, isolated temp directory via {@link TempDirs}
 * (injected by {@link TempDirCleanupExtension}) — no manual cleanup: a
 * passing test's directory is deleted automatically; a failing test's is
 * left on disk for inspection.</p>
 */
@ExtendWith(TempDirCleanupExtension.class)
@DisplayName("Unit tests for JsonMapper — vault marker")
class JsonMapperTest {

    Path tempDir;

    @BeforeEach
    void setUp(TempDirs tempDirs) throws IOException {
        tempDir = tempDirs.newDir("JsonMapperTest", "marker");
    }

    // No @AfterEach — TempDirCleanupExtension.testSuccessful() owns cleanup,
    // conditionally on the test outcome.

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