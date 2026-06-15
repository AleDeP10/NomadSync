package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.exception.GitException;
import io.aledep10.nomadsync.exception.NetworkException;
import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.util.CommandUtil;
import io.aledep10.nomadsync.util.TestConstants;
import io.aledep10.nomadsync.util.TestUtil;
import io.aledep10.nomadsync.util.TestVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link GitService}.
 *
 * <p>Each test runs against a real Git repository created in a temporary directory
 * via {@link TestUtil#getTestVault(String)}. The repository is initialised in
 * {@code @BeforeEach} and deleted in {@code @AfterEach}, guaranteeing full isolation
 * between test cases.</p>
 *
 * <p>{@link LogService} and the {@link TestVault} are shared across all tests
 * ({@code @BeforeAll}) — they carry no mutable state relevant to Git operations.</p>
 *
 * <p>{@link VaultService} is mocked — snapshot creation is not under test here.
 * The snapshot workflow is covered by {@code VaultServiceTest}.</p>
 *
 * <p>All {@link GitService} methods under test accept a {@code String vaultPath}
 * argument taken from {@link TestVault#vaultPath()}.</p>
 */
class GitServiceTest {

    static TestVault testVault;
    static LogService logService;

    String vaultPath;
    GitService gitService;

    // ── Shared setup ──────────────────────────────────────────────────────────

    @BeforeAll
    static void prepareSharedState() throws IOException {
        TestVault testVault  = TestUtil.getTestVault("GitServiceTest");
        logService = new LogService(TestUtil.forLogService(testVault, LogLevel.DEBUG));
    }

    // ── Per-test setup / teardown ─────────────────────────────────────────────

    @BeforeEach
    void setUp() throws GitException, NetworkException, IOException, InterruptedException {
        testVault  = TestUtil.getTestVault("GitServiceTest");
        vaultPath = testVault.vaultPath().toString();

        CommandUtil.runCommand(vaultPath, List.of(TestConstants.GIT_EXECUTABLE, "init"));
        CommandUtil.runCommand(vaultPath, List.of(TestConstants.GIT_EXECUTABLE,
                "config", "user.name",  TestConstants.GIT_NAME));
        CommandUtil.runCommand(vaultPath, List.of(TestConstants.GIT_EXECUTABLE,
                "config", "user.email", TestConstants.GIT_EMAIL));

        Properties properties = new Properties();
        properties.setProperty("git.executable", TestConstants.GIT_EXECUTABLE);
        gitService = new GitService(properties, mock(VaultService.class), logService);
    }

    @AfterEach
    void tearDown() throws IOException {
        TestUtil.cleanup(testVault);
    }

    // ── hasChanges() ──────────────────────────────────────────────────────────

    /**
     * Verifies that {@code hasChanges()} returns {@code false} on a clean working tree —
     * no unstaged modifications in tracked files.
     */
    @Test
    void hasChanges_noChanges_returnsFalse() throws GitException, NetworkException, IOException, InterruptedException {
        createAndCommitFile("tmp.txt", "initial content");

        assertThat(gitService.hasChanges(vaultPath)).isFalse();
    }

    /**
     * Verifies that {@code hasChanges()} returns {@code true} when a tracked file
     * has been modified but not staged.
     */
    @Test
    void hasChanges_withUnstagedModification_returnsTrue() throws GitException, NetworkException, IOException, InterruptedException {
        createAndCommitFile("tmp.txt", "initial content");
        Files.writeString(testVault.vaultPath().resolve("tmp.txt"), "updated content");

        assertThat(gitService.hasChanges(vaultPath)).isTrue();
    }

    // ── hasUncommittedChanges() ───────────────────────────────────────────────

    /**
     * Verifies that {@code hasUncommittedChanges()} returns {@code false} on a
     * clean working tree with no staged or unstaged changes.
     */
    @Test
    void hasUncommittedChanges_cleanTree_returnsFalse() throws GitException, NetworkException, IOException, InterruptedException {
        createAndCommitFile("tmp.txt", "initial content");

        assertThat(gitService.hasUncommittedChanges(vaultPath)).isFalse();
    }

    /**
     * Verifies that {@code hasUncommittedChanges()} returns {@code true} when a
     * tracked file has been modified but not staged.
     */
    @Test
    void hasUncommittedChanges_withUnstagedModification_returnsTrue() throws GitException, NetworkException, IOException, InterruptedException {
        createAndCommitFile("tmp.txt", "initial content");
        Files.writeString(testVault.vaultPath().resolve("tmp.txt"), "updated content");

        assertThat(gitService.hasUncommittedChanges(vaultPath)).isTrue();
    }

    /**
     * Verifies that {@code hasUncommittedChanges()} returns {@code true} for a file
     * that has been staged but not yet committed — the key behavioural difference
     * from {@code hasChanges()}.
     */
    @Test
    void hasUncommittedChanges_withStagedNotCommitted_returnsTrue() throws GitException, NetworkException, IOException, InterruptedException {
        Files.writeString(testVault.vaultPath().resolve("new.txt"), "staged content");
        CommandUtil.runCommand(vaultPath, List.of(TestConstants.GIT_EXECUTABLE, "add", "."));

        assertThat(gitService.hasUncommittedChanges(vaultPath)).isTrue();
    }

    // ── commitLocal() ─────────────────────────────────────────────────────────

    /**
     * Verifies that {@code commitLocal()} returns exit code {@code 0} and clears
     * the working tree when there are changes to commit.
     */
    @Test
    void commitLocal_withChanges_returnsZeroAndClearsChanges() throws GitException, NetworkException, IOException, InterruptedException {
        Files.writeString(testVault.vaultPath().resolve("tmp.txt"), "content");

        int exitCode = gitService.commitLocal(vaultPath, "test commit");

        assertThat(exitCode).isEqualTo(0);
        assertThat(gitService.hasChanges(vaultPath)).isFalse();
    }

    /**
     * Verifies that {@code commitLocal()} returns a non-zero exit code when there
     * is nothing to commit.
     */
    @Test
    void commitLocal_withNoChanges_returnsNonZero() throws GitException, NetworkException, InterruptedException {
        int exitCode = gitService.commitLocal(vaultPath, "empty commit");

        assertThat(exitCode).isNotEqualTo(0);
    }

    // ── stash() / stashPop() ──────────────────────────────────────────────────

    /**
     * Verifies the stash round-trip: stash hides uncommitted changes,
     * stash pop restores them.
     */
    @Test
    void stash_shelvesThenPopRestores() throws GitException, NetworkException, IOException, InterruptedException {
        createAndCommitFile("tmp.txt", "initial content");
        Files.writeString(testVault.vaultPath().resolve("tmp.txt"), "modified content");
        assertThat(gitService.hasUncommittedChanges(vaultPath)).isTrue();

        gitService.stash(vaultPath);
        assertThat(gitService.hasUncommittedChanges(vaultPath)).isFalse();

        gitService.stashPop(vaultPath);
        assertThat(gitService.hasUncommittedChanges(vaultPath)).isTrue();
    }

    /**
     * Verifies that stashing on a clean working tree does not throw — the guard
     * in {@link io.aledep10.nomadsync.orchestrator.SyncOrchestrator} prevents
     * this in production, but {@link GitService} must not crash if called directly.
     */
    @Test
    void stash_onCleanTree_doesNotThrow() throws GitException, NetworkException, InterruptedException {
        gitService.stash(vaultPath);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createAndCommitFile(String filename, String content)
            throws GitException, NetworkException, IOException, InterruptedException {
        Files.writeString(testVault.vaultPath().resolve(filename), content);
        CommandUtil.runCommand(vaultPath, List.of(TestConstants.GIT_EXECUTABLE, "add", "."));
        CommandUtil.runCommand(vaultPath, List.of(TestConstants.GIT_EXECUTABLE,
                "commit", "-m", "test: initial commit"));
    }
}