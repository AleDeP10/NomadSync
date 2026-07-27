package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.exception.WorkspaceException;
import io.aledep10.nomadsync.exception.WorkspaceIntegrityException;
import io.aledep10.nomadsync.exception.WorkspaceNotFoundException;
import io.aledep10.nomadsync.marker.MarkerType;
import io.aledep10.nomadsync.util.JsonMapper;
import io.aledep10.nomadsync.util.TempDirCleanupExtension;
import io.aledep10.nomadsync.util.TempDirs;
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
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkspaceService}.
 */
@ExtendWith(TempDirCleanupExtension.class)
@DisplayName("Unit tests for WorkspaceService")
class WorkspaceServiceTest {

    Path installDir;
    LogService logService;
    MarkerService markerService;
    WorkspaceService workspaceService;
    File registryFile;

    @BeforeEach
    void setUp(TempDirs tempDirs) throws IOException {
        installDir = tempDirs.newDir("WorkspaceServiceTest", "install");
        registryFile = installDir.resolve("workspaces.json").toFile();

        Properties properties = new Properties();
        logService = new LogService(properties, installDir);
        markerService = new MarkerService(properties, logService);
        workspaceService = new WorkspaceService(installDir, markerService, logService);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("load")
    class LoadTests {

        @Test
        @DisplayName("returns an empty list when workspaces.json does not exist")
        void missingFile_returnsEmptyList() throws WorkspaceException {
            assertThat(workspaceService.load()).isEmpty();
        }

        @Test
        @DisplayName("populates the in-memory cache, queryable via findAll/findByName/findDefault")
        void populatesCache() throws IOException, WorkspaceException {
            WorkspaceEntry defaultWorkspace = new WorkspaceEntry("default", installDir.resolve("default").toString(), true);
            WorkspaceEntry secondary = new WorkspaceEntry("laptop-work", installDir.resolve("laptop").toString(), false);
            JsonMapper.saveWorkspacesToFile(registryFile, List.of(defaultWorkspace, secondary));

            List<WorkspaceEntry> loaded = workspaceService.load();

            assertThat(loaded).hasSize(2);
            assertThat(workspaceService.findAll()).hasSize(2);
            assertThat(workspaceService.findByName("laptop-work")).isPresent();
            assertThat(workspaceService.findDefault()).isPresent();
            assertThat(workspaceService.findDefault().get().getWorkspaceName()).isEqualTo("default");
        }

        @Test
        @DisplayName("throws WorkspaceIntegrityException when two entries share the same workspaceName")
        void duplicateWorkspaceName_throws() throws IOException {
            // Two entries sharing a name can only be produced by hand-editing the
            // file — WorkspaceEntry's own uniqueness is enforced by this service,
            // not by JsonMapper. Write the raw JSON directly to bypass that.
            Files.writeString(registryFile.toPath(), """
                    { "workspaces": [
                        { "workspaceName": "default", "path": "%s", "isDefault": true },
                        { "workspaceName": "default", "path": "%s" }
                    ] }
                    """.formatted(jsonSafe(installDir.resolve("a")), jsonSafe(installDir.resolve("b"))));

            assertThatThrownBy(() -> workspaceService.load())
                    .isInstanceOf(WorkspaceIntegrityException.class);
        }

        @Test
        @DisplayName("throws WorkspaceIntegrityException when no entry is marked default and the registry is non-empty")
        void noDefaultEntry_throws() throws IOException {
            WorkspaceEntry onlyWorkspace = new WorkspaceEntry("laptop-work", installDir.resolve("laptop").toString(), false);
            JsonMapper.saveWorkspacesToFile(registryFile, List.of(onlyWorkspace));

            assertThatThrownBy(() -> workspaceService.load())
                    .isInstanceOf(WorkspaceIntegrityException.class);
        }

        @Test
        @DisplayName("throws WorkspaceIntegrityException when more than one entry is marked default")
        void multipleDefaultEntries_throws() throws IOException {
            Files.writeString(registryFile.toPath(), """
                    { "workspaces": [
                        { "workspaceName": "a", "path": "%s", "isDefault": true },
                        { "workspaceName": "b", "path": "%s", "isDefault": true }
                    ] }
                    """.formatted(jsonSafe(installDir.resolve("a")), jsonSafe(installDir.resolve("b"))));

            assertThatThrownBy(() -> workspaceService.load())
                    .isInstanceOf(WorkspaceIntegrityException.class);
        }
    }

    @Nested
    @DisplayName("save")
    class SaveTests {

        @Test
        @DisplayName("create followed by a fresh load round-trips the registered entry")
        void createThenLoad_roundTrips() throws WorkspaceException {
            workspaceService.create("default", installDir.resolve("ws-1").toString());

            WorkspaceService reloaded = new WorkspaceService(installDir, markerService, logService);
            List<WorkspaceEntry> loaded = reloaded.load();

            assertThat(loaded).hasSize(1);
            assertThat(loaded.get(0).getWorkspaceName()).isEqualTo("default");
        }
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("creates the directory, claims the WORKSPACE marker, and registers the entry")
        void createsDirectoryMarkerAndEntry() throws WorkspaceException {
            String path = installDir.resolve("ws-1").toString();

            WorkspaceEntry created = workspaceService.create("default", path);

            assertThat(created.getWorkspaceName()).isEqualTo("default");
            assertThat(Files.isDirectory(Path.of(path))).isTrue();
            assertThat(Files.isDirectory(Path.of(path).resolve(MarkerType.WORKSPACE.folderName()))).isTrue();
        }

        @Test
        @DisplayName("the very first entry ever created is automatically marked default")
        void firstEntry_isAutomaticallyDefault() throws WorkspaceException {
            WorkspaceEntry created = workspaceService.create("default", installDir.resolve("ws-1").toString());

            assertThat(created.isDefault()).isTrue();
        }

        @Test
        @DisplayName("a second entry is not automatically marked default")
        void secondEntry_isNotAutomaticallyDefault() throws WorkspaceException {
            workspaceService.create("default", installDir.resolve("ws-1").toString());

            WorkspaceEntry second = workspaceService.create("laptop-work", installDir.resolve("ws-2").toString());

            assertThat(second.isDefault()).isFalse();
        }

        @Test
        @DisplayName("throws WorkspaceIntegrityException on a duplicate workspaceName")
        void duplicateName_throws() throws WorkspaceException {
            workspaceService.create("default", installDir.resolve("ws-1").toString());

            assertThatThrownBy(() -> workspaceService.create("default", installDir.resolve("ws-2").toString()))
                    .isInstanceOf(WorkspaceIntegrityException.class);
        }
    }

    // ── add ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("add")
    class AddTests {

        @Test
        @DisplayName("registers an existing directory that already has a WORKSPACE marker")
        void registersExistingMarkedDirectory() throws IOException, WorkspaceException {
            Path existing = installDir.resolve("already-there");
            Files.createDirectories(existing.resolve(MarkerType.WORKSPACE.folderName()));

            WorkspaceEntry added = workspaceService.add("default", existing.toString());

            assertThat(added.getWorkspaceName()).isEqualTo("default");
            assertThat(workspaceService.findByName("default")).isPresent();
        }

        @Test
        @DisplayName("throws WorkspaceException when the directory has no WORKSPACE marker")
        void missingMarker_throws() throws IOException {
            Path unmarked = installDir.resolve("unmarked");
            Files.createDirectories(unmarked);

            assertThatThrownBy(() -> workspaceService.add("default", unmarked.toString()))
                    .isInstanceOf(WorkspaceException.class);
        }

        @Test
        @DisplayName("throws WorkspaceIntegrityException on a duplicate workspaceName")
        void duplicateName_throws() throws IOException, WorkspaceException {
            Path existing = installDir.resolve("already-there");
            Files.createDirectories(existing.resolve(MarkerType.WORKSPACE.folderName()));
            workspaceService.add("default", existing.toString());

            Path secondExisting = installDir.resolve("also-there");
            Files.createDirectories(secondExisting.resolve(MarkerType.WORKSPACE.folderName()));

            assertThatThrownBy(() -> workspaceService.add("default", secondExisting.toString()))
                    .isInstanceOf(WorkspaceIntegrityException.class);
        }
    }

    // ── rename ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("rename")
    class RenameTests {

        @Test
        @DisplayName("changes workspaceName, leaves path and isDefault untouched")
        void renamesSuccessfully() throws WorkspaceException {
            workspaceService.create("default", installDir.resolve("ws-1").toString());

            WorkspaceEntry renamed = workspaceService.rename("default", "primary");

            assertThat(renamed.getWorkspaceName()).isEqualTo("primary");
            assertThat(renamed.isDefault()).isTrue();
            assertThat(workspaceService.findByName("default")).isEmpty();
            assertThat(workspaceService.findByName("primary")).isPresent();
        }

        @Test
        @DisplayName("throws WorkspaceNotFoundException when workspaceName is not registered")
        void unknownName_throws() {
            assertThatThrownBy(() -> workspaceService.rename("ghost", "primary"))
                    .isInstanceOf(WorkspaceNotFoundException.class);
        }

        @Test
        @DisplayName("throws WorkspaceIntegrityException when newWorkspaceName collides with an existing entry")
        void collidingNewName_throws() throws WorkspaceException {
            workspaceService.create("default", installDir.resolve("ws-1").toString());
            workspaceService.create("laptop-work", installDir.resolve("ws-2").toString());

            assertThatThrownBy(() -> workspaceService.rename("laptop-work", "default"))
                    .isInstanceOf(WorkspaceIntegrityException.class);
        }
    }

    // ── remove ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("remove")
    class RemoveTests {

        @Test
        @DisplayName("unregisters the entry without touching the local directory")
        void removesWithoutDeletingDirectory() throws WorkspaceException {
            workspaceService.create("default", installDir.resolve("ws-1").toString());
            workspaceService.create("laptop-work", installDir.resolve("ws-2").toString());
            String path = workspaceService.findByName("laptop-work").get().getPath();

            workspaceService.remove("laptop-work");

            assertThat(workspaceService.findByName("laptop-work")).isEmpty();
            assertThat(Files.isDirectory(Path.of(path))).isTrue();
        }

        @Test
        @DisplayName("throws WorkspaceNotFoundException when workspaceName is not registered")
        void unknownName_throws() {
            assertThatThrownBy(() -> workspaceService.remove("ghost"))
                    .isInstanceOf(WorkspaceNotFoundException.class);
        }

        @Test
        @DisplayName("refuses to remove the current default workspace")
        void refusesToRemoveDefault() throws WorkspaceException {
            workspaceService.create("default", installDir.resolve("ws-1").toString());

            assertThatThrownBy(() -> workspaceService.remove("default"))
                    .isInstanceOf(WorkspaceException.class);
            assertThat(workspaceService.findByName("default")).isPresent();
        }
    }

    // ── erase ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("erase")
    class EraseTests {

        @Test
        @DisplayName("unregisters the entry and physically deletes the directory")
        void erasesEntryAndDirectory() throws WorkspaceException {
            workspaceService.create("default", installDir.resolve("ws-1").toString());
            workspaceService.create("laptop-work", installDir.resolve("ws-2").toString());
            String path = workspaceService.findByName("laptop-work").get().getPath();

            workspaceService.erase("laptop-work");

            assertThat(workspaceService.findByName("laptop-work")).isEmpty();
            assertThat(Files.exists(Path.of(path))).isFalse();
        }

        @Test
        @DisplayName("throws WorkspaceNotFoundException when workspaceName is not registered")
        void unknownName_throws() {
            assertThatThrownBy(() -> workspaceService.erase("ghost"))
                    .isInstanceOf(WorkspaceNotFoundException.class);
        }

        @Test
        @DisplayName("refuses to erase the current default workspace")
        void refusesToEraseDefault() throws WorkspaceException {
            workspaceService.create("default", installDir.resolve("ws-1").toString());

            assertThatThrownBy(() -> workspaceService.erase("default"))
                    .isInstanceOf(WorkspaceException.class);
            assertThat(workspaceService.findByName("default")).isPresent();
        }
    }

    // ── use ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("use")
    class UseTests {

        @Test
        @DisplayName("promotes the target to default and demotes the previous default, atomically")
        void togglesDefaultAtomically() throws WorkspaceException {
            workspaceService.create("default", installDir.resolve("ws-1").toString());
            workspaceService.create("laptop-work", installDir.resolve("ws-2").toString());

            WorkspaceEntry promoted = workspaceService.use("laptop-work");

            assertThat(promoted.isDefault()).isTrue();
            assertThat(workspaceService.findByName("default").get().isDefault()).isFalse();
            assertThat(workspaceService.findDefault()).isPresent();
            assertThat(workspaceService.findDefault().get().getWorkspaceName()).isEqualTo("laptop-work");
        }

        @Test
        @DisplayName("throws WorkspaceNotFoundException when workspaceName is not registered")
        void unknownName_throws() {
            assertThatThrownBy(() -> workspaceService.use("ghost"))
                    .isInstanceOf(WorkspaceNotFoundException.class);
        }
    }

    // ── resolveWorkspacePath (NomadSync-WSP-002) ───────────────────────────────

    @Nested
    @DisplayName("resolveWorkspacePath")
    class ResolveWorkspacePathTests {

        @Test
        @DisplayName("returns null when both flags are absent")
        void bothAbsent_returnsNull() throws WorkspaceException {
            assertThat(workspaceService.resolveWorkspacePath(null, null)).isNull();
        }

        @Test
        @DisplayName("resolves --workspace alone via the registry")
        void nameOnly_resolvesViaRegistry() throws WorkspaceException {
            workspaceService.create("default", installDir.resolve("ws-1").toString());
            String expectedPath = workspaceService.findByName("default").get().getPath();

            assertThat(workspaceService.resolveWorkspacePath("default", null)).isEqualTo(expectedPath);
        }

        @Test
        @DisplayName("resolves --workspacePath alone as a ghost path, no registry lookup")
        void pathOnly_resolvesAsGhost() throws WorkspaceException {
            String ghostPath = installDir.resolve("ghost").toString();

            assertThat(workspaceService.resolveWorkspacePath(null, ghostPath))
                    .isEqualTo(Path.of(ghostPath).toAbsolutePath().normalize().toString());
        }

        @Test
        @DisplayName("both flags present and consistent - no error, resolved path returned")
        void bothPresentConsistent_noError() throws WorkspaceException {
            workspaceService.create("default", installDir.resolve("ws-1").toString());
            String path = workspaceService.findByName("default").get().getPath();

            assertThat(workspaceService.resolveWorkspacePath("default", path)).isEqualTo(path);
        }

        @Test
        @DisplayName("both flags present and inconsistent - throws before any side effect")
        void bothPresentInconsistent_throws() throws WorkspaceException {
            workspaceService.create("default", installDir.resolve("ws-1").toString());

            assertThatThrownBy(() -> workspaceService.resolveWorkspacePath(
                    "default", installDir.resolve("somewhere-else").toString()))
                    .isInstanceOf(WorkspaceException.class);
        }

        @Test
        @DisplayName("throws WorkspaceNotFoundException when --workspace does not match any registered entry")
        void unknownName_throws() {
            assertThatThrownBy(() -> workspaceService.resolveWorkspacePath("ghost", null))
                    .isInstanceOf(WorkspaceNotFoundException.class);
        }
    }

    private static String jsonSafe(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
