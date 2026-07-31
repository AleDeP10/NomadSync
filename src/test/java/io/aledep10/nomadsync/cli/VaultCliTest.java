package io.aledep10.nomadsync.cli;

import io.aledep10.nomadsync.exception.VaultException;
import io.aledep10.nomadsync.marker.MarkerType;
import io.aledep10.nomadsync.service.*;
import io.aledep10.nomadsync.util.FileUtil;
import io.aledep10.nomadsync.util.TempDirCleanupExtension;
import io.aledep10.nomadsync.util.TempDirs;
import io.aledep10.nomadsync.vault.Vault;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link VaultCli}.
 *
 * <p>Written against the current, post-{@code AbstractCli} instance-based
 * shape — {@code VaultCli} is constructed once per test in {@code @BeforeEach}
 * and its handlers are called directly, no reflection.</p>
 *
 * <p>Deliberately does <strong>not</strong> re-test the generic flag-validation
 * mechanics ({@code hasUnknownFlags}/{@code hasBlankRequiredFlags}/
 * {@code hasBlankOptionalValue}) — those are covered once, thoroughly, in
 * {@code AbstractCliTest}. Every test here targets domain logic specific to a
 * {@code VaultCli} handler.</p>
 *
 * <p>Real {@link VaultService}/{@link MarkerService}/{@link GitService}/
 * {@link LogService} instances, not mocks — same convention as the rest of
 * the test suite (isolated temp directories per test via {@link TempDirs}).
 * Interactive confirmation prompts ({@code y/N}) are bypassed with
 * {@code --force} wherever the test's actual subject is not the prompt
 * itself; one test exercises the decline path directly via a redirected
 * {@code System.in}.</p>
 *
 * <p><strong>Assumption flagged for verification</strong>: {@link GitService}'s
 * constructor is assumed to follow the same {@code (Properties, LogService)}
 * shape as {@link MarkerService} — I have not seen {@code GitService.java}
 * directly in this session. If the real signature differs, only the
 * {@code @BeforeEach} setup needs adjusting; no test body depends on its
 * internal shape.</p>
 */
@ExtendWith(TempDirCleanupExtension.class)
@DisplayName("Unit tests for VaultCli")
class VaultCliTest {

    Path installDir;
    LogService logService;
    MarkerService markerService;
    GitignoreService gitignoreService;
    GitService gitService;
    VaultService vaultService;
    VaultCli vaultCli;

    @BeforeEach
    void setUp(TempDirs tempDirs) throws IOException {
        installDir = tempDirs.newDir("VaultCliTest", "install");
        Properties properties = new Properties();
        logService = new LogService(properties, installDir);
        markerService = new MarkerService(properties, logService);
        gitignoreService = new GitignoreService(logService);
        vaultService = new VaultService(installDir, markerService, gitignoreService, logService);
        gitService = new GitService(properties, vaultService, gitignoreService, logService);
        vaultCli = new VaultCli(vaultService, markerService, gitService, logService);
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
            int result = vaultCli.execute("bogus", flags());

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("dispatches 'list' to handleVaultList")
        void list_dispatchesCorrectly() {
            int result = vaultCli.execute("list", flags());

            assertThat(result).isEqualTo(0);
        }
    }

    // ── create ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleVaultCreate")
    class HandleVaultCreateTests {

        @Test
        @DisplayName("initialises a repository and registers the vault")
        void createsAndRegisters() {
            String path = installDir.resolve("vault-1").toString();

            int result = vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, path));

            assertThat(result).isEqualTo(0);
            assertThat(vaultService.findByRepoSlug("Alice/portfolio")).isPresent();
            assertThat(Files.isDirectory(Path.of(path).resolve(".git"))).isTrue();
        }

        @Test
        @DisplayName("is a no-op (2) when the repoSlug is already registered")
        void duplicateRepoSlug_isNoOp() {
            String path = installDir.resolve("vault-1").toString();
            vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, path));

            int result = vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio",
                            VaultCli.FLAG_PATH, installDir.resolve("vault-2").toString()));

            assertThat(result).isEqualTo(2);
        }

        @Test
        @DisplayName("errors when the target path exists, is non-empty, and has no .git")
        void nonEmptyNonGitPath_errors() throws IOException {
            Path path = installDir.resolve("occupied");
            Files.createDirectories(path);
            Files.writeString(path.resolve("something.txt"), "unrelated content");

            int result = vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio",
                            VaultCli.FLAG_PATH, path.toString()));

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("is a no-op (2) when the target path already contains .git")
        void existingGitRepo_isNoOp() throws IOException {
            Path path = installDir.resolve("already-git");
            Files.createDirectories(path.resolve(".git"));

            int result = vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio",
                            VaultCli.FLAG_PATH, path.toString()));

            assertThat(result).isEqualTo(2);
        }
    }

    // ── add ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleVaultAdd")
    class HandleVaultAddTests {

        @Test
        @DisplayName("registers an existing git repository")
        void registersExistingRepo() throws VaultException {
            String path = installDir.resolve("vault-1").toString();
            vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "temp", VaultCli.FLAG_PATH, path));
            vaultService.delete(vaultService.findByRepoSlug("Alice/temp").orElseThrow().getId());

            int result = vaultCli.handleVaultAdd(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, path));

            assertThat(result).isEqualTo(0);
            assertThat(vaultService.findByRepoSlug("Alice/portfolio")).isPresent();
        }

        @Test
        @DisplayName("errors when the path is not a git repository")
        void notAGitRepo_errors() throws IOException {
            Path path = installDir.resolve("plain-folder");
            Files.createDirectories(path);

            int result = vaultCli.handleVaultAdd(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio",
                            VaultCli.FLAG_PATH, path.toString()));

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("errors when the path does not exist")
        void missingPath_errors() {
            int result = vaultCli.handleVaultAdd(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH,
                            installDir.resolve("does-not-exist").toString()));

            assertThat(result).isEqualTo(1);
        }
    }

    // ── update ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleVaultUpdate")
    class HandleVaultUpdateTests {

        @Test
        @DisplayName("is a no-op (2) when no optional flag is provided")
        void noFlags_isNoOp() {
            String path = installDir.resolve("vault-1").toString();
            vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, path));

            int result = vaultCli.handleVaultUpdate(flags(VaultCli.FLAG_VAULT, "Alice/portfolio"));

            assertThat(result).isEqualTo(2);
        }

        @Test
        @DisplayName("updates the name and persists it")
        void updatesName() {
            String path = installDir.resolve("vault-1").toString();
            vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, path));

            int result = vaultCli.handleVaultUpdate(
                    flags(VaultCli.FLAG_VAULT, "Alice/portfolio", VaultCli.FLAG_NAME, "renamed"));

            assertThat(result).isEqualTo(0);
            assertThat(vaultService.findByRepoSlug("Alice/renamed")).isPresent();
        }

        /**
         * Regression test for the in-place mutation bug (NomadSync-VLT-013,
         * reintroduced and fixed in this same session): a path change made via
         * setters directly on the resolved (shared) instance would make
         * {@code VaultService.update}'s change detection always see "no
         * change", silently skipping marker claim/release at the new
         * location. This test fails against that buggy version and passes
         * against the corrected one — it is the one test in this file that
         * exists specifically to catch that regression again if reintroduced.
         */
        @Test
        @DisplayName("a path change actually claims the marker at the new location")
        void pathChange_claimsMarkerAtNewLocation() throws IOException {
            String oldPath = installDir.resolve("vault-1").toString();
            String newPath = installDir.resolve("vault-1-moved").toString();
            vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, oldPath));

            // 'vault update --path' assumes the user already relocated the files
            // themselves — unlike 'vault relocate', it never moves anything physically.
            // Simulate that here, same as handleVaultRelocate does internally: the raw
            // copy carries over the OLD marker folder, which must not occupy the new
            // location's claim slot.
            Path source = Path.of(oldPath);
            Path target = Path.of(newPath);
            FileUtil.copyRecursively(source, target);
            FileUtil.deleteRecursively(target.resolve(MarkerType.VAULT.folderName()));

            int result = vaultCli.handleVaultUpdate(
                    flags(VaultCli.FLAG_VAULT, "Alice/portfolio", VaultCli.FLAG_PATH, newPath));

            assertThat(result).isEqualTo(0);
            Vault updated = vaultService.findByRepoSlug("Alice/portfolio").orElseThrow();
            assertThat(updated.getPath()).isEqualTo(target.toAbsolutePath().normalize().toString());
            assertThat(Files.isDirectory(target.resolve(".nomadsync-vault"))).isTrue();
            assertThat(Files.isDirectory(source.resolve(".nomadsync-vault"))).isFalse();
        }

        @Test
        @DisplayName("--force relocates to a genuinely different path: marker moves, old directory is fully removed")
        void forceRelocatesToNewPath() {
            String oldPath = installDir.resolve("vault-1").toString();
            String newPath = installDir.resolve("vault-1-relocated").toString();
            vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, oldPath));

            int result = vaultCli.handleVaultRelocate(
                    flags(VaultCli.FLAG_VAULT, "Alice/portfolio", VaultCli.FLAG_PATH, newPath, AbstractCli.FLAG_FORCE, ""));

            assertThat(result).isEqualTo(0);

            Vault relocated = vaultService.findByRepoSlug("Alice/portfolio").orElseThrow();
            Path target = Path.of(newPath);
            assertThat(relocated.getPath()).isEqualTo(target.toAbsolutePath().normalize().toString());

            // New location: git history reset but present, marker claimed there
            assertThat(Files.isDirectory(target.resolve(".git"))).isTrue();
            assertThat(Files.isDirectory(target.resolve(MarkerType.VAULT.folderName()))).isTrue();

            // Old location: fully gone, not just unmarked
            assertThat(Files.exists(Path.of(oldPath))).isFalse();
        }

        @Test
        @DisplayName("--path explicitly equal to the current path is a no-op (2), nothing is touched physically")
        void samePathExplicit_isNoOp() {
            String path = installDir.resolve("vault-1").toString();
            vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, path));

            int result = vaultCli.handleVaultRelocate(
                    flags(VaultCli.FLAG_VAULT, "Alice/portfolio", VaultCli.FLAG_PATH, path, AbstractCli.FLAG_FORCE, ""));

            assertThat(result).isEqualTo(2);
            Vault unchanged = vaultService.findByRepoSlug("Alice/portfolio").orElseThrow();
            Path source = Path.of(path);
            assertThat(unchanged.getPath()).isEqualTo(source.toAbsolutePath().normalize().toString());
            assertThat(Files.isDirectory(source.resolve(MarkerType.VAULT.folderName()))).isTrue();
        }

        @Test
        @DisplayName("resolution failure logs the error and lists registered vaults")
        void unknownVault_errors() {
            int result = vaultCli.handleVaultUpdate(
                    flags(VaultCli.FLAG_VAULT, "ghost/vault", VaultCli.FLAG_NAME, "x"));

            assertThat(result).isEqualTo(1);
        }
    }

    // ── remove ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleVaultRemove")
    class HandleVaultRemoveTests {

        @Test
        @DisplayName("--force removes without prompting, keeps the local directory")
        void forceRemoves() {
            String path = installDir.resolve("vault-1").toString();
            vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, path));

            int result = vaultCli.handleVaultRemove(
                    flags(VaultCli.FLAG_VAULT, "Alice/portfolio", AbstractCli.FLAG_FORCE, ""));

            assertThat(result).isEqualTo(0);
            assertThat(vaultService.findByRepoSlug("Alice/portfolio")).isEmpty();
            assertThat(Files.isDirectory(Path.of(path))).isTrue();
        }

        @Test
        @DisplayName("declining the confirmation prompt is a no-op (2)")
        void declinePrompt_isNoOp() {
            String path = installDir.resolve("vault-1").toString();
            vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, path));

            InputStream originalIn = System.in;
            System.setIn(new ByteArrayInputStream("n\n".getBytes()));
            try {
                int result = vaultCli.handleVaultRemove(flags(VaultCli.FLAG_VAULT, "Alice/portfolio"));
                assertThat(result).isEqualTo(2);
                assertThat(vaultService.findByRepoSlug("Alice/portfolio")).isPresent();
            } finally {
                System.setIn(originalIn);
            }
        }
    }

    // ── relocate ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleVaultRelocate")
    class HandleVaultRelocateTests {

        @Test
        @DisplayName("is a no-op (2) when nothing was requested")
        void noChanges_isNoOp() {
            String path = installDir.resolve("vault-1").toString();
            vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, path));

            int result = vaultCli.handleVaultRelocate(
                    flags(VaultCli.FLAG_VAULT, "Alice/portfolio", AbstractCli.FLAG_FORCE, ""));

            assertThat(result).isEqualTo(2);
        }

        @Test
        @DisplayName("rejects a credential-only request, directs to 'vault update'")
        void credentialOnly_rejected() {
            String path = installDir.resolve("vault-1").toString();
            vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, path));

            int result = vaultCli.handleVaultRelocate(
                    flags(VaultCli.FLAG_VAULT, "Alice/portfolio", VaultCli.FLAG_GIT_TOKEN, "abc", AbstractCli.FLAG_FORCE, ""));

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("--force relocates to a new owner, discarding history")
        void forceRelocatesToNewOwner() {
            String path = installDir.resolve("vault-1").toString();
            vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, path));

            int result = vaultCli.handleVaultRelocate(
                    flags(VaultCli.FLAG_VAULT, "Alice/portfolio", VaultCli.FLAG_OWNER, "Bob", AbstractCli.FLAG_FORCE, ""));

            assertThat(result).isEqualTo(0);
            assertThat(vaultService.findByRepoSlug("Bob/portfolio")).isPresent();
            assertThat(vaultService.findByRepoSlug("Alice/portfolio")).isEmpty();
        }

        @Test
        @DisplayName("rejects --git.* even when a structural change is also requested")
        void gitFlagsWithStructuralChange_stillRejected() {
            String path = installDir.resolve("vault-1").toString();
            vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, path));

            int result = vaultCli.handleVaultRelocate(
                    flags(VaultCli.FLAG_VAULT, "Alice/portfolio", VaultCli.FLAG_OWNER, "Bob",
                            VaultCli.FLAG_GIT_TOKEN, "abc", AbstractCli.FLAG_FORCE, ""));

            assertThat(result).isEqualTo(1);
            // nothing moved, nothing changed — the rejection happens before any side effect
            assertThat(vaultService.findByRepoSlug("Alice/portfolio")).isPresent();
            assertThat(vaultService.findByRepoSlug("Bob/portfolio")).isEmpty();
        }
    }

    // ── list ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleVaultList")
    class HandleVaultListTests {

        @Test
        @DisplayName("succeeds with an empty registry")
        void emptyRegistry_succeeds() {
            assertThat(vaultCli.handleVaultList(flags())).isEqualTo(0);
        }

        @Test
        @DisplayName("succeeds and lists every registered vault")
        void listsRegisteredVaults() {
            String path = installDir.resolve("vault-1").toString();
            vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, path));

            assertThat(vaultCli.handleVaultList(flags())).isEqualTo(0);
        }
    }

    // ── show ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleVaultShow")
    class HandleVaultShowTests {

        @Test
        @DisplayName("shows mandatory fields for a resolved vault")
        void showsResolvedVault() {
            String path = installDir.resolve("vault-1").toString();
            vaultCli.handleVaultCreate(
                    flags(VaultCli.FLAG_OWNER, "Alice", VaultCli.FLAG_NAME, "portfolio", VaultCli.FLAG_PATH, path));

            int result = vaultCli.handleVaultShow(flags(VaultCli.FLAG_VAULT, "Alice/portfolio"), 5);

            assertThat(result).isEqualTo(0);
        }

        @Test
        @DisplayName("errors when the vault cannot be resolved")
        void unresolvedVault_errors() {
            int result = vaultCli.handleVaultShow(flags(VaultCli.FLAG_VAULT, "ghost/vault"), 5);

            assertThat(result).isEqualTo(1);
        }
    }
}