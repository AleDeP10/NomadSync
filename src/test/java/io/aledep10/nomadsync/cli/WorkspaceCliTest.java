package io.aledep10.nomadsync.cli;

import io.aledep10.nomadsync.marker.MarkerType;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.service.MarkerService;
import io.aledep10.nomadsync.service.WorkspaceService;
import io.aledep10.nomadsync.util.TempDirCleanupExtension;
import io.aledep10.nomadsync.util.TempDirs;
import io.aledep10.nomadsync.workspace.WorkspaceEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkspaceCli}.
 *
 * <p>Same conventions as {@code VaultCliTest}: real {@link WorkspaceService}/
 * {@link MarkerService}/{@link LogService} instances, isolated temp
 * directories via {@link TempDirs}, generic flag-validation mechanics left
 * to {@code AbstractCliTest} — every test here targets domain logic specific
 * to a {@code WorkspaceCli} handler. Interactive confirmation prompts are
 * bypassed with {@code --force} except where the prompt itself is the
 * subject.</p>
 */
@ExtendWith(TempDirCleanupExtension.class)
@DisplayName("Unit tests for WorkspaceCli")
class WorkspaceCliTest {

    Path installDir;
    LogService logService;
    MarkerService markerService;
    WorkspaceService workspaceService;
    WorkspaceCli workspaceCli;

    @BeforeEach
    void setUp(TempDirs tempDirs) throws IOException {
        installDir = tempDirs.newDir("WorkspaceCliTest", "install");
        Properties properties = new Properties();
        logService = new LogService(properties, installDir);
        markerService = new MarkerService(properties, logService);
        workspaceService = new WorkspaceService(installDir, markerService, logService);
        workspaceCli = new WorkspaceCli(workspaceService, markerService, logService);
    }

    private Map<String, String> flags(String... kv) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
        return map;
    }

    // ── execute — dispatch ──────────────────────────────────────────────────

    @Nested
    @DisplayName("execute")
    class ExecuteTests {

        @Test
        @DisplayName("returns 1 and logs an error for an unrecognized subcommand")
        void unknownSubcommand_returnsOne() {
            assertThat(workspaceCli.execute("bogus", flags())).isEqualTo(1);
        }

        @Test
        @DisplayName("dispatches 'create' to handleWorkspaceCreate")
        void create_dispatchesCorrectly() {
            String path = installDir.resolve("ws-1").toString();

            int result = workspaceCli.execute("create",
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "default", WorkspaceCli.FLAG_PATH, path));

            assertThat(result).isEqualTo(0);
        }
    }

    // ── create ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleWorkspaceCreate")
    class HandleWorkspaceCreateTests {

        @Test
        @DisplayName("creates the directory, claims the marker, registers the entry as default (first ever)")
        void createsAndRegistersAsDefault() {
            String path = installDir.resolve("ws-1").toString();

            int result = workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "default", WorkspaceCli.FLAG_PATH, path));

            assertThat(result).isEqualTo(0);
            WorkspaceEntry created = workspaceService.findByName("default").orElseThrow();
            assertThat(created.isDefault()).isTrue();
            assertThat(Files.isDirectory(Path.of(path).resolve(MarkerType.WORKSPACE.folderName()))).isTrue();
        }

        @Test
        @DisplayName("errors on a duplicate workspaceName")
        void duplicateName_errors() {
            String path = installDir.resolve("ws-1").toString();
            workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "default", WorkspaceCli.FLAG_PATH, path));

            int result = workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "default",
                            WorkspaceCli.FLAG_PATH, installDir.resolve("ws-2").toString()));

            assertThat(result).isEqualTo(1);
        }
    }

    // ── add ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleWorkspaceAdd")
    class HandleWorkspaceAddTests {

        @Test
        @DisplayName("registers an existing directory that already has a WORKSPACE marker")
        void registersExistingMarkedDirectory() throws IOException {
            Path path = installDir.resolve("already-there");
            Files.createDirectories(path.resolve(MarkerType.WORKSPACE.folderName()));

            int result = workspaceCli.handleWorkspaceAdd(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "default", WorkspaceCli.FLAG_PATH, path.toString()));

            assertThat(result).isEqualTo(0);
            assertThat(workspaceService.findByName("default")).isPresent();
        }

        @Test
        @DisplayName("errors when the directory has no WORKSPACE marker")
        void missingMarker_errors() throws IOException {
            Path path = installDir.resolve("unmarked");
            Files.createDirectories(path);

            int result = workspaceCli.handleWorkspaceAdd(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "default", WorkspaceCli.FLAG_PATH, path.toString()));

            assertThat(result).isEqualTo(1);
        }
    }

    // ── rename ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleWorkspaceRename")
    class HandleWorkspaceRenameTests {

        @Test
        @DisplayName("changes workspaceName, leaves path and isDefault untouched")
        void renamesSuccessfully() {
            String path = installDir.resolve("ws-1").toString();
            workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "default", WorkspaceCli.FLAG_PATH, path));

            int result = workspaceCli.handleWorkspaceRename(
                    flags(WorkspaceCli.FLAG_WORKSPACE, "default", WorkspaceCli.FLAG_WORKSPACE_NAME, "primary"));

            assertThat(result).isEqualTo(0);
            assertThat(workspaceService.findByName("default")).isEmpty();
            WorkspaceEntry renamed = workspaceService.findByName("primary").orElseThrow();
            assertThat(renamed.isDefault()).isTrue();
        }

        @Test
        @DisplayName("errors when --workspace does not resolve to a registered entry")
        void unknownWorkspace_errors() {
            int result = workspaceCli.handleWorkspaceRename(
                    flags(WorkspaceCli.FLAG_WORKSPACE, "ghost", WorkspaceCli.FLAG_WORKSPACE_NAME, "primary"));

            assertThat(result).isEqualTo(1);
        }
    }

    // ── relocate ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleWorkspaceRelocate")
    class HandleWorkspaceRelocateTests {

        @Test
        @DisplayName("--force physically moves the workspace directory")
        void forceRelocates() {
            String oldPath = installDir.resolve("ws-1").toString();
            String newPath = installDir.resolve("ws-1-moved").toString();
            workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "default", WorkspaceCli.FLAG_PATH, oldPath));

            int result = workspaceCli.handleWorkspaceRelocate(
                    flags(WorkspaceCli.FLAG_WORKSPACE, "default", WorkspaceCli.FLAG_PATH, newPath, AbstractCli.FLAG_FORCE, ""));

            assertThat(result).isEqualTo(0);
            WorkspaceEntry relocated = workspaceService.findByName("default").orElseThrow();
            assertThat(relocated.getPath()).isEqualTo(Path.of(newPath).toAbsolutePath().normalize().toString());
            assertThat(Files.exists(Path.of(oldPath))).isFalse();
            assertThat(Files.isDirectory(Path.of(newPath).resolve(MarkerType.WORKSPACE.folderName()))).isTrue();
        }

        @Test
        @DisplayName("errors when --workspace does not resolve to a registered entry")
        void unknownWorkspace_errors() {
            int result = workspaceCli.handleWorkspaceRelocate(
                    flags(WorkspaceCli.FLAG_WORKSPACE, "ghost", WorkspaceCli.FLAG_PATH,
                            installDir.resolve("x").toString(), AbstractCli.FLAG_FORCE, ""));

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("declining the confirmation prompt is a no-op (2)")
        void declinePrompt_isNoOp() {
            String oldPath = installDir.resolve("ws-1").toString();
            String newPath = installDir.resolve("ws-1-moved").toString();
            workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "default", WorkspaceCli.FLAG_PATH, oldPath));

            InputStream originalIn = System.in;
            System.setIn(new ByteArrayInputStream("n\n".getBytes()));
            try {
                int result = workspaceCli.handleWorkspaceRelocate(
                        flags(WorkspaceCli.FLAG_WORKSPACE, "default", WorkspaceCli.FLAG_PATH, newPath));
                assertThat(result).isEqualTo(2);
                assertThat(workspaceService.findByName("default").orElseThrow().getPath())
                        .isEqualTo(Path.of(oldPath).toAbsolutePath().normalize().toString());
            } finally {
                System.setIn(originalIn);
            }
        }
    }

    // ── remove ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleWorkspaceRemove")
    class HandleWorkspaceRemoveTests {

        @Test
        @DisplayName("--force unregisters without touching the directory")
        void forceRemoves() {
            String defaultPath = installDir.resolve("ws-1").toString();
            String secondaryPath = installDir.resolve("ws-2").toString();
            workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "default", WorkspaceCli.FLAG_PATH, defaultPath));
            workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "secondary", WorkspaceCli.FLAG_PATH, secondaryPath));

            int result = workspaceCli.handleWorkspaceRemove(
                    flags(WorkspaceCli.FLAG_WORKSPACE, "secondary", AbstractCli.FLAG_FORCE, ""));

            assertThat(result).isEqualTo(0);
            assertThat(workspaceService.findByName("secondary")).isEmpty();
            assertThat(Files.isDirectory(Path.of(secondaryPath))).isTrue();
        }

        @Test
        @DisplayName("refuses to remove the current default workspace")
        void refusesToRemoveDefault() {
            String path = installDir.resolve("ws-1").toString();
            workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "default", WorkspaceCli.FLAG_PATH, path));

            int result = workspaceCli.handleWorkspaceRemove(
                    flags(WorkspaceCli.FLAG_WORKSPACE, "default", AbstractCli.FLAG_FORCE, ""));

            assertThat(result).isEqualTo(1);
            assertThat(workspaceService.findByName("default")).isPresent();
        }
    }

    // ── erase ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleWorkspaceErase")
    class HandleWorkspaceEraseTests {

        @Test
        @DisplayName("--force unregisters and physically deletes the directory")
        void forceErases() {
            String defaultPath = installDir.resolve("ws-1").toString();
            String secondaryPath = installDir.resolve("ws-2").toString();
            workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "default", WorkspaceCli.FLAG_PATH, defaultPath));
            workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "secondary", WorkspaceCli.FLAG_PATH, secondaryPath));

            int result = workspaceCli.handleWorkspaceErase(
                    flags(WorkspaceCli.FLAG_WORKSPACE, "secondary", AbstractCli.FLAG_FORCE, ""));

            assertThat(result).isEqualTo(0);
            assertThat(workspaceService.findByName("secondary")).isEmpty();
            assertThat(Files.exists(Path.of(secondaryPath))).isFalse();
        }

        @Test
        @DisplayName("refuses to erase the current default workspace")
        void refusesToEraseDefault() {
            String path = installDir.resolve("ws-1").toString();
            workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "default", WorkspaceCli.FLAG_PATH, path));

            int result = workspaceCli.handleWorkspaceErase(
                    flags(WorkspaceCli.FLAG_WORKSPACE, "default", AbstractCli.FLAG_FORCE, ""));

            assertThat(result).isEqualTo(1);
            assertThat(Files.isDirectory(Path.of(path))).isTrue();
        }
    }

    // ── use ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleWorkspaceUse")
    class HandleWorkspaceUseTests {

        @Test
        @DisplayName("promotes the target to default, demotes the previous default")
        void togglesDefault() {
            String defaultPath = installDir.resolve("ws-1").toString();
            String secondaryPath = installDir.resolve("ws-2").toString();
            workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "default", WorkspaceCli.FLAG_PATH, defaultPath));
            workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "secondary", WorkspaceCli.FLAG_PATH, secondaryPath));

            int result = workspaceCli.handleWorkspaceUse(flags(WorkspaceCli.FLAG_WORKSPACE, "secondary"));

            assertThat(result).isEqualTo(0);
            assertThat(workspaceService.findByName("secondary").orElseThrow().isDefault()).isTrue();
            assertThat(workspaceService.findByName("default").orElseThrow().isDefault()).isFalse();
        }

        @Test
        @DisplayName("errors when --workspace does not resolve to a registered entry")
        void unknownWorkspace_errors() {
            int result = workspaceCli.handleWorkspaceUse(flags(WorkspaceCli.FLAG_WORKSPACE, "ghost"));

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("--path alone (no --workspace) errors with guidance to 'add' then 'use'")
        void pathAloneWithoutWorkspace_errorsWithGuidance() {
            int result = workspaceCli.handleWorkspaceUse(
                    flags(WorkspaceCli.FLAG_PATH, installDir.resolve("ghost").toString()));

            assertThat(result).isEqualTo(1);
        }
    }

    // ── list ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleWorkspaceList")
    class HandleWorkspaceListTests {

        @Test
        @DisplayName("succeeds with an empty registry")
        void emptyRegistry_succeeds() {
            assertThat(workspaceCli.handleWorkspaceList(flags())).isEqualTo(0);
        }

        @Test
        @DisplayName("succeeds and lists every registered workspace")
        void listsRegisteredWorkspaces() {
            workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "default",
                            WorkspaceCli.FLAG_PATH, installDir.resolve("ws-1").toString()));
            workspaceCli.handleWorkspaceCreate(
                    flags(WorkspaceCli.FLAG_WORKSPACE_NAME, "secondary",
                            WorkspaceCli.FLAG_PATH, installDir.resolve("ws-2").toString()));

            assertThat(workspaceCli.handleWorkspaceList(flags())).isEqualTo(0);
        }
    }
}
