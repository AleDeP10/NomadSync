package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.config.NomadPropertiesLoader;
import io.aledep10.nomadsync.exception.GitException;
import io.aledep10.nomadsync.exception.NetworkException;
import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.vault.Vault;
import io.aledep10.nomadsync.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Integration tests for {@link GitService}.
 *
 * <p>Each test runs against a real Git repository created via a fresh
 * {@link TestVault}, obtained through the injected {@link TempDirs}
 * (per-test, registered for conditional cleanup — see
 * {@link TempDirCleanupExtension}). Any additional ad-hoc directory a test
 * needs (a bare remote, a second clone, a standalone init/reset target) is
 * likewise obtained via {@code tempDirs.newDir(...)}, never a raw
 * {@code Files.createTempDirectory} call with manual cleanup — a failing
 * test's entire filesystem footprint is left intact for inspection instead
 * of being deleted regardless of outcome.</p>
 *
 * <p>{@link LogService} and the class-shared {@code sharedVault} are created
 * once in {@code @BeforeAll} and cleaned up in {@code @AfterAll} only if
 * every test in this class passed — see {@link ClassFailureTracker}.</p>
 *
 * <p>{@link VaultService} is mocked for most tests — snapshot/conflict persistence
 * is not under test there. {@link SynchronizeTests} is the exception: it verifies
 * the exact calls {@code synchronize()} makes on {@code VaultService}
 * ({@code makeVaultSnapshot(Vault)}, {@code saveConflict(...)}), since that
 * interaction contract is precisely what {@code synchronize()} owns.</p>
 *
 * <p>All {@link GitService} methods under test receive a {@link Vault} built from
 * {@link TestVault#vaultPath()} — matching the production contract established
 * in GRM M7 Sprint B.</p>
 *
 * <h2>Credential resolution</h2>
 * <p>Tests for {@code bootstrapVault()} cover three tiers of credential resolution:</p>
 * <ol>
 *   <li>No credentials — method is a no-op, no exception thrown.</li>
 *   <li>Global credentials via {@code Properties} — applied when vault fields are null.</li>
 *   <li>Per-vault credentials — take precedence over global properties.</li>
 * </ol>
 */
@ExtendWith({TempDirCleanupExtension.class, ClassFailureTracker.class})
@DisplayName("Unit tests for GitService")
class GitServiceTest {

    static  NomadPropertiesLoader loader;
    static TestVault sharedVault;
    static LogService logService;

    TestVault testVault;
    Vault vault;
    GitService gitService;

    // ── Shared setup ──────────────────────────────────────────────────────────

    @BeforeAll
    static void prepareSharedState() throws IOException {
        sharedVault = TestUtil.getTestVault("GitServiceTest-shared");
        loader = NomadPropertiesLoader.forTesting(
                TestUtil.forLogService(sharedVault, LogLevel.DEBUG));
        logService  = new LogService(loader, sharedVault.rootPath());
    }

    @AfterAll
    static void tearDownAll(ExtensionContext context) throws IOException {
        logService.close();
        if (!ClassFailureTracker.anyTestFailed(context)) {
            TestUtil.cleanup(sharedVault);
        }
        // if any test failed: sharedVault (and its log file) is left on disk
        // for inspection
    }

    // ── Per-test setup ───────────────────────────────────────────────────────

    @BeforeEach
    void setUp(TempDirs tempDirs)
            throws GitException, NetworkException, IOException, InterruptedException {
        testVault = tempDirs.newVault("GitServiceTest");
        String vaultPath = testVault.vaultPath().toString();

        vault = new Vault(UUID.randomUUID().toString(), "owner", "test-vault", vaultPath);

        CommandUtil.runCommand(vaultPath, List.of(TestConstants.GIT_EXECUTABLE, "init"));
        CommandUtil.runCommand(vaultPath, List.of(TestConstants.GIT_EXECUTABLE,
                "config", "user.name",  TestConstants.GIT_NAME));
        CommandUtil.runCommand(vaultPath, List.of(TestConstants.GIT_EXECUTABLE,
                "config", "user.email", TestConstants.GIT_EMAIL));

        gitService = new GitService(loader, mock(VaultService.class), mock(GitignoreService.class), logService);
    }

    // No @AfterEach — testVault is registered with the injected TempDirs above
    // and cleaned up conditionally (on success only) by TempDirCleanupExtension.

    // ── hasChanges() ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("hasChanges")
    class HasChangesTests {

        @Test
        @DisplayName("returns false on a clean working tree")
        void hasChanges_noChanges_returnsFalse()
                throws GitException, NetworkException, IOException, InterruptedException {
            createAndCommitFile("tmp.txt", "initial content");

            assertThat(gitService.hasChanges(vault)).isFalse();
        }

        @Test
        @DisplayName("returns true when a tracked file has unstaged modifications")
        void hasChanges_withUnstagedModification_returnsTrue()
                throws GitException, NetworkException, IOException, InterruptedException {
            createAndCommitFile("tmp.txt", "initial content");
            Files.writeString(testVault.vaultPath().resolve("tmp.txt"), "updated content");

            assertThat(gitService.hasChanges(vault)).isTrue();
        }
    }

    // ── hasUncommittedChanges() ───────────────────────────────────────────────

    @Nested
    @DisplayName("hasUncommittedChanges")
    class HasUncommittedChangesTests {

        @Test
        @DisplayName("returns false on a clean working tree")
        void hasUncommittedChanges_cleanTree_returnsFalse()
                throws GitException, NetworkException, IOException, InterruptedException {
            createAndCommitFile("tmp.txt", "initial content");

            assertThat(gitService.hasUncommittedChanges(vault)).isFalse();
        }

        @Test
        @DisplayName("returns true when a tracked file has unstaged modifications")
        void hasUncommittedChanges_withUnstagedModification_returnsTrue()
                throws GitException, NetworkException, IOException, InterruptedException {
            createAndCommitFile("tmp.txt", "initial content");
            Files.writeString(testVault.vaultPath().resolve("tmp.txt"), "updated content");

            assertThat(gitService.hasUncommittedChanges(vault)).isTrue();
        }

        @Test
        @DisplayName("returns true for staged but uncommitted files - key difference from hasChanges")
        void hasUncommittedChanges_withStagedNotCommitted_returnsTrue()
                throws GitException, NetworkException, IOException, InterruptedException {
            Files.writeString(testVault.vaultPath().resolve("new.txt"), "staged content");
            CommandUtil.runCommand(testVault.vaultPath().toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "add", "."));

            assertThat(gitService.hasUncommittedChanges(vault)).isTrue();
        }
    }

    // ── commitLocal() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("commitLocal")
    class CommitLocalTests {

        @Test
        @DisplayName("returns exit code 0 and clears working tree when there are changes")
        void commitLocal_withChanges_returnsZeroAndClearsChanges()
                throws GitException, IOException, InterruptedException {
            Files.writeString(testVault.vaultPath().resolve("tmp.txt"), "content");

            int exitCode = gitService.commitLocal(vault, "test commit");

            assertThat(exitCode).isEqualTo(0);
            assertThat(gitService.hasChanges(vault)).isFalse();
        }

        @Test
        @DisplayName("returns non-zero exit code when there is nothing to commit")
        void commitLocal_withNoChanges_returnsNonZero()
                throws GitException, InterruptedException {
            int exitCode = gitService.commitLocal(vault, "empty commit");

            assertThat(exitCode).isNotEqualTo(0);
        }
    }

    // ── stash() / stashPop() ──────────────────────────────────────────────────

    @Nested
    @DisplayName("stash and stashPop")
    class StashTests {

        @Test
        @DisplayName("stash hides uncommitted changes; stashPop restores them")
        void stash_shelvesThenPopRestores()
                throws GitException, NetworkException, IOException, InterruptedException {
            createAndCommitFile("tmp.txt", "initial content");
            Files.writeString(testVault.vaultPath().resolve("tmp.txt"), "modified content");
            assertThat(gitService.hasUncommittedChanges(vault)).isTrue();

            gitService.stash(vault);
            assertThat(gitService.hasUncommittedChanges(vault)).isFalse();

            gitService.stashPop(vault);
            assertThat(gitService.hasUncommittedChanges(vault)).isTrue();
        }

        @Test
        @DisplayName("stash on a clean working tree does not throw")
        void stash_onCleanTree_doesNotThrow()
                throws GitException, InterruptedException {
            gitService.stash(vault);
        }
    }

    // ── .gitignore normalization before staging ─────────────────────────────────

    @Nested
    @DisplayName(".gitignore normalization before staging")
    class GitignoreNormalizationTests {

        GitignoreService realGitignoreService;
        GitService gs;

        @BeforeEach
        void setUpRealGitignoreService() {
            realGitignoreService = new GitignoreService(logService);
            gs = new GitService(loader, mock(VaultService.class), realGitignoreService, logService);
        }

        @Test
        @DisplayName("commitLocal() creates a missing .gitignore before staging — "
                + "SYSTEM patterns are present even though no .gitignore existed yet")
        void commitLocal_missingGitignore_createsProtectionBeforeStaging() throws Exception {
            Files.writeString(testVault.vaultPath().resolve("tmp.txt"), "content");
            // no .gitignore exists yet

            gs.commitLocal(vault, "test commit");

            String gitignoreContent = Files.readString(testVault.vaultPath().resolve(".gitignore"));
            assertThat(gitignoreContent).contains(".git");
            assertThat(gitignoreContent).contains(".DS_Store");
        }

        @Test
        @DisplayName("commitLocal() restores a manually tampered .gitignore (missing a SYSTEM "
                + "pattern) before staging — the exact scenario that could leak a secret")
        void commitLocal_tamperedGitignore_restoresMissingSystemPatternBeforeStaging() throws Exception {
            // ".git" removed by hand — simulates tampering
            Files.writeString(testVault.vaultPath().resolve(".gitignore"),
                    "# SYSTEM PATTERNS - DO NOT TOUCH!\n.DS_Store\nThumbs.db\ndesktop.ini\n");
            Files.writeString(testVault.vaultPath().resolve("tmp.txt"), "content");

            gs.commitLocal(vault, "test commit");

            String gitignoreContent = Files.readString(testVault.vaultPath().resolve(".gitignore"));
            assertThat(gitignoreContent).contains(".git");
        }

        @Test
        @DisplayName("end-to-end: a file already covered by a default APP pattern is never staged — "
                + "proves normalization runs BEFORE 'git add -A', not after")
        void commitLocal_appPatternFile_excludedFromCommit() throws Exception {
            Files.createDirectories(testVault.vaultPath().resolve(".obsidian"));
            Files.writeString(testVault.vaultPath().resolve(".obsidian").resolve("cache"), "cache-data");
            // .obsidian/cache is a default Obsidian APP pattern — always active, even before
            // any .gitignore exists, since load() creates one from scratch on first use

            gs.commitLocal(vault, "test commit");

            String committedFiles = CommandUtil.runCommandWithOutput(testVault.vaultPath().toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "show", "--stat", "--format="));
            assertThat(committedFiles).doesNotContain(".obsidian/cache");
        }

        @Test
        @DisplayName("stash() also normalises .gitignore before staging, for the same reason as commitLocal()")
        void stash_tamperedGitignore_restoresMissingSystemPatternBeforeStaging() throws Exception {
            Files.writeString(testVault.vaultPath().resolve(".gitignore"),
                    "# SYSTEM PATTERNS - DO NOT TOUCH!\n.DS_Store\nThumbs.db\ndesktop.ini\n");
            Files.writeString(testVault.vaultPath().resolve("tmp.txt"), "initial content");
            CommandUtil.runCommand(testVault.vaultPath().toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "add", "."));
            CommandUtil.runCommand(testVault.vaultPath().toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "commit", "-m", "seed"));
            Files.writeString(testVault.vaultPath().resolve("tmp.txt"), "modified content");

            gs.stash(vault);

            String gitignoreContent = Files.readString(testVault.vaultPath().resolve(".gitignore"));
            assertThat(gitignoreContent).contains(".git");
        }
    }

    // ── bootstrapVault() ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("bootstrapVault")
    class BootstrapVaultTests {

        @Test
        @DisplayName("is a no-op when no per-vault and no global credentials are set")
        void bootstrapVault_noCredentials_doesNotThrow()
                throws GitException, InterruptedException {
            Vault noCredVault = new Vault(UUID.randomUUID().toString(),
                    "owner", "no-cred-vault", testVault.vaultPath().toString());

            gitService.bootstrapVault(noCredVault);
            // no exception = pass — no Git config commands were executed
        }

        @Test
        @DisplayName("writes user.name to .git/config when per-vault gitName is set")
        void bootstrapVault_withVaultGitName_setsLocalUserName()
                throws GitException, NetworkException, InterruptedException {
            Vault named = new Vault(UUID.randomUUID().toString(),
                    "owner", "named-vault", testVault.vaultPath().toString(),
                    "Vault User", null, null, null, null, null);

            gitService.bootstrapVault(named);

            String result = CommandUtil.runCommandWithOutput(testVault.vaultPath().toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "config", "user.name"));
            assertThat(result.trim()).isEqualTo("Vault User");
        }

        @Test
        @DisplayName("falls back to global git.name property when per-vault gitName is null")
        void bootstrapVault_vaultNameNull_fallsBackToGlobalProperty()
                throws GitException, NetworkException, InterruptedException {
            NomadPropertiesLoader localLoader = gitLoader("git.name", "Global User");
            GitService gs = new GitService(localLoader,
                    mock(VaultService.class), mock(GitignoreService.class), logService);

            Vault noNameVault = new Vault(UUID.randomUUID().toString(),
                    "owner", "fallback-vault", testVault.vaultPath().toString());

            gs.bootstrapVault(noNameVault);

            String result = CommandUtil.runCommandWithOutput(testVault.vaultPath().toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "config", "user.name"));
            assertThat(result.trim()).isEqualTo("Global User");
        }

        @Test
        @DisplayName("per-vault gitName takes precedence over global git.name property")
        void bootstrapVault_vaultNameOverridesGlobal()
                throws GitException, NetworkException, InterruptedException {
            NomadPropertiesLoader localLoader = gitLoader("git.name", "Global User");
            GitService gs = new GitService(localLoader,
                    mock(VaultService.class), mock(GitignoreService.class), logService);

            Vault overrideVault = new Vault(UUID.randomUUID().toString(),
                    "owner", "override-vault", testVault.vaultPath().toString(),
                    "Per-Vault User", null, null, null, null, null);

            gs.bootstrapVault(overrideVault);

            String result = CommandUtil.runCommandWithOutput(testVault.vaultPath().toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "config", "user.name"));
            assertThat(result.trim()).isEqualTo("Per-Vault User");
        }

        @Test
        @DisplayName("warns (does not throw) when credentials disappear but a NomadSync-configured remote already exists")
        void bootstrapVault_credentialsRemoved_existingRemoteLeftUntouched()
                throws GitException, NetworkException, InterruptedException {
            NomadPropertiesLoader withCreds = gitLoader("git.name", "User", "git.email", "u@x.com",
                    "git.username", "user", "git.token", "ghp_initial");
            GitService gsWithCreds = new GitService(withCreds,
                    mock(VaultService.class), mock(GitignoreService.class), logService);
            Vault vault = new Vault(UUID.randomUUID().toString(), "owner", "drift-vault", testVault.vaultPath().toString());
            gsWithCreds.bootstrapVault(vault);   // establishes the remote with a token-embedded URL

            NomadPropertiesLoader withoutCreds = gitLoader("git.name", "User", "git.email", "u@x.com");
            GitService gsWithoutCreds = new GitService(withoutCreds,
                    mock(VaultService.class), mock(GitignoreService.class), logService);

            gsWithoutCreds.bootstrapVault(vault);   // must not throw

            String result = CommandUtil.runCommandWithOutput(testVault.vaultPath().toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "remote", "get-url", "origin"));
            assertThat(result).contains("ghp_initial");   // stale URL left exactly as it was, not reset
        }

        static NomadPropertiesLoader gitLoader(String... extraKeyValues) {
            Properties properties = TestUtil.forLogService(sharedVault, LogLevel.DEBUG);
            for (int i = 0; i < extraKeyValues.length; i += 2) {
                properties.setProperty(extraKeyValues[i], extraKeyValues[i + 1]);
            }
            return NomadPropertiesLoader.forTesting(properties);
        }
    }

    // ── init() ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("init")
    class InitTests {

        @Test
        @DisplayName("initialises a new git repository when .git/ is absent")
        void init_noGitDir_initialisesRepository(TempDirs tempDirs)
                throws GitException, InterruptedException, IOException {
            Path tempDir = tempDirs.newDir("GitServiceTest", "init-test");
            Vault freshVault = new Vault(UUID.randomUUID().toString(),
                    "alice", "fresh-vault", tempDir.toString());

            gitService.init(freshVault);

            assertThat(tempDir.resolve(".git").toFile()).exists();
        }

        @Test
        @DisplayName("is a no-op when .git/ already exists")
        void init_gitDirPresent_isNoOp()
                throws GitException, InterruptedException, IOException {
            Path gitConfig = testVault.vaultPath().resolve(".git").resolve("config");
            java.nio.file.attribute.FileTime before =
                    Files.getLastModifiedTime(gitConfig);

            gitService.init(vault);

            java.nio.file.attribute.FileTime after =
                    Files.getLastModifiedTime(gitConfig);
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("does not throw when called twice on the same path")
        void init_calledTwice_doesNotThrow()
                throws GitException, InterruptedException {
            gitService.init(vault);
            gitService.init(vault);
            // no exception = pass
        }
    }

    // ── reset() ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reset")
    class ResetTests {

        @Test
        @DisplayName("removes an existing .git/ directory and reinitialises a fresh repository")
        void reset_gitDirPresent_discardsHistoryAndReinitialises(TempDirs tempDirs)
                throws GitException, InterruptedException, IOException {
            Path tempDir = tempDirs.newDir("GitServiceTest", "reset-test");
            Vault testSubject = new Vault(UUID.randomUUID().toString(),
                    "alice", "reset-vault", tempDir.toString());

            gitService.init(testSubject);
            Path marker = tempDir.resolve(".git").resolve("HISTORY_MARKER");
            Files.writeString(marker, "should not survive reset()");

            gitService.reset(testSubject);

            assertThat(tempDir.resolve(".git").toFile()).exists().isDirectory();
            assertThat(marker.toFile()).doesNotExist();
        }

        @Test
        @DisplayName("initialises a repository when .git/ is absent")
        void reset_noGitDir_initialisesRepository(TempDirs tempDirs)
                throws GitException, InterruptedException, IOException {
            Path tempDir = tempDirs.newDir("GitServiceTest", "reset-test");
            Vault freshVault = new Vault(UUID.randomUUID().toString(),
                    "alice", "fresh-vault", tempDir.toString());

            gitService.reset(freshVault);

            assertThat(tempDir.resolve(".git").toFile()).exists().isDirectory();
        }

        @Test
        @DisplayName("does not throw when called twice in a row")
        void reset_calledTwice_doesNotThrow(TempDirs tempDirs)
                throws GitException, InterruptedException, IOException {
            Path tempDir = tempDirs.newDir("GitServiceTest", "reset-test");
            Vault testSubject = new Vault(UUID.randomUUID().toString(),
                    "alice", "reset-twice-vault", tempDir.toString());

            gitService.reset(testSubject);
            gitService.reset(testSubject);
            // no exception = pass — the second call removes the .git/ created
            // by the first call and reinitialises again
        }
    }

    // ── synchronize() ──────────────────────────────────────────────────────────

    /**
     * Uses a real local bare repository as the remote, rather than mocking Git
     * itself — {@code synchronize()}'s value is entirely in its orchestration of
     * real {@code pull}/{@code push}/merge behaviour, which a mock cannot exercise
     * meaningfully. {@code VaultService} remains mocked: these tests verify *that*
     * it is called correctly ({@code makeVaultSnapshot(Vault)}, {@code saveConflict}),
     * not the snapshot/conflict persistence mechanics themselves (covered by
     * {@code VaultServiceTest}).
     *
     * <p>The default branch is explicitly renamed to {@code main} right after the
     * first commit — relying on the environment's {@code init.defaultBranch}
     * would make these tests fragile across machines with different Git configs.</p>
     */
    @Nested
    @DisplayName("synchronize")
    class SynchronizeTests {

        Path remoteBarePath;
        VaultService vaultServiceMock;
        GitignoreService gitignoreServiceMock;
        GitService gs;

        @BeforeEach
        void setUpRemote(TempDirs tempDirs)
                throws GitException, NetworkException, InterruptedException, IOException {
            remoteBarePath = tempDirs.newDir("GitServiceTest", "sync-remote");
            CommandUtil.runCommand(remoteBarePath.toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "init", "--bare"));

            createAndCommitFile("seed.txt", "seed content");
            CommandUtil.runCommand(testVault.vaultPath().toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "branch", "-M", "main"));
            CommandUtil.runCommand(testVault.vaultPath().toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "remote", "add", "origin",
                            remoteBarePath.toString()));
            CommandUtil.runCommand(testVault.vaultPath().toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "push", "-u", "origin", "main"));
            CommandUtil.runCommand(remoteBarePath.toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "symbolic-ref", "HEAD", "refs/heads/main"));

            vaultServiceMock = mock(VaultService.class);
            gitignoreServiceMock = mock(GitignoreService.class);
            gs = new GitService(loader, vaultServiceMock, gitignoreServiceMock, logService);
        }

        // No @AfterEach — remoteBarePath is registered with the injected
        // TempDirs above and cleaned up conditionally by TempDirCleanupExtension.

        @Test
        @DisplayName("commits local changes, pulls, and pushes when there is no conflict")
        void synchronize_noConflict_commitsPullsAndPushes() throws Exception {
            Files.writeString(testVault.vaultPath().resolve("new-file.txt"), "new content");

            List<String> conflicts = gs.synchronize(vault);

            assertThat(conflicts.isEmpty()).isTrue();
            assertThat(gitService.hasUncommittedChanges(vault)).isFalse();
            verify(vaultServiceMock, times(0)).makeVaultSnapshot(any());
        }

        @Test
        @DisplayName("does not throw when there is nothing to commit and the remote is already up to date")
        void synchronize_nothingToCommit_doesNotThrow() throws Exception {
            List<String> conflicts = gs.synchronize(vault);

            assertThat(conflicts.isEmpty()).isTrue();
            verify(vaultServiceMock, times(0)).makeVaultSnapshot(any());
        }

        @Test
        @DisplayName("on conflict: snapshots via VaultService.makeVaultSnapshot(Vault), "
                + "resolves local-wins, and saves the remote version of each conflicted file")
        void synchronize_withConflict_snapshotsAndResolvesLocalWins(TempDirs tempDirs) throws Exception {
            Path otherClone = tempDirs.newDir("GitServiceTest", "sync-other-clone");
            CommandUtil.runCommand(otherClone.toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "clone", remoteBarePath.toString(), "."));
            Files.writeString(otherClone.resolve("seed.txt"), "remote change");
            CommandUtil.runCommand(otherClone.toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "add", "."));
            CommandUtil.runCommand(otherClone.toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "config", "user.name", TestConstants.GIT_NAME));
            CommandUtil.runCommand(otherClone.toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "config", "user.email", TestConstants.GIT_EMAIL));
            CommandUtil.runCommand(otherClone.toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "commit", "-m", "remote change"));
            CommandUtil.runCommand(otherClone.toString(),
                    List.of(TestConstants.GIT_EXECUTABLE, "push", "origin", "main"));

            Files.writeString(testVault.vaultPath().resolve("seed.txt"), "local change");

            List<String> conflicts = gs.synchronize(vault);

            assertThat(conflicts.contains("seed.txt")).isTrue();
            verify(vaultServiceMock, times(1)).makeVaultSnapshot(vault);
            verify(vaultServiceMock, times(1)).saveConflict(any(), eq("seed.txt"), any());
            assertThat(Files.readString(testVault.vaultPath().resolve("seed.txt")))
                    .isEqualTo("local change");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createAndCommitFile(String filename, String content)
            throws GitException, NetworkException, IOException, InterruptedException {
        Files.writeString(testVault.vaultPath().resolve(filename), content);
        CommandUtil.runCommand(testVault.vaultPath().toString(),
                List.of(TestConstants.GIT_EXECUTABLE, "add", "."));
        CommandUtil.runCommand(testVault.vaultPath().toString(),
                List.of(TestConstants.GIT_EXECUTABLE, "commit", "-m", "test: initial commit"));
    }
}