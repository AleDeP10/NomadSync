package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.exception.GitException;
import io.aledep10.nomadsync.exception.NetworkException;
import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.orchestrator.Vault;
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
import java.util.UUID;

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
 * <p>{@link LogService} is shared across all tests ({@code @BeforeAll}) —
 * it carries no mutable state relevant to Git operations.</p>
 *
 * <p>{@link VaultService} is mocked — snapshot creation is not under test here.
 * The snapshot workflow is covered by {@code VaultServiceTest}.</p>
 *
 * <p>All {@link GitService} methods under test receive a {@link Vault} built from
 * {@link TestVault#vaultPath()} — matching the production contract established
 * in GRM M7 Sprint B.</p>
 */
class GitServiceTest {

    static LogService logService;

    TestVault testVault;
    Vault vault;
    GitService gitService;

    // ── Shared setup ──────────────────────────────────────────────────────────

    @BeforeAll
    static void prepareSharedState() throws IOException {
        TestVault shared = TestUtil.getTestVault("GitServiceTest-shared");
        logService = new LogService(TestUtil.forLogService(shared, LogLevel.DEBUG));
    }

    // ── Per-test setup / teardown ─────────────────────────────────────────────

    @BeforeEach
    void setUp() throws GitException, NetworkException, IOException, InterruptedException {
        testVault = TestUtil.getTestVault("GitServiceTest");
        String vaultPath = testVault.vaultPath().toString();

        vault = new Vault(UUID.randomUUID().toString(), "AleDeP10", "test-vault", vaultPath);

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
    void hasChanges_noChanges_returnsFalse()
            throws GitException, NetworkException, IOException, InterruptedException {
        createAndCommitFile("tmp.txt", "initial content");

        assertThat(gitService.hasChanges(vault)).isFalse();
    }

    /**
     * Verifies that {@code hasChanges()} returns {@code true} when a tracked file
     * has been modified but not staged.
     */
    @Test
    void hasChanges_withUnstagedModification_returnsTrue()
            throws GitException, NetworkException, IOException, InterruptedException {
        createAndCommitFile("tmp.txt", "initial content");
        Files.writeString(testVault.vaultPath().resolve("tmp.txt"), "updated content");

        assertThat(gitService.hasChanges(vault)).isTrue();
    }

    // ── hasUncommittedChanges() ───────────────────────────────────────────────

    /**
     * Verifies that {@code hasUncommittedChanges()} returns {@code false} on a
     * clean working tree with no staged or unstaged changes.
     */
    @Test
    void hasUncommittedChanges_cleanTree_returnsFalse()
            throws GitException, NetworkException, IOException, InterruptedException {
        createAndCommitFile("tmp.txt", "initial content");

        assertThat(gitService.hasUncommittedChanges(vault)).isFalse();
    }

    /**
     * Verifies that {@code hasUncommittedChanges()} returns {@code true} when a
     * tracked file has been modified but not staged.
     */
    @Test
    void hasUncommittedChanges_withUnstagedModification_returnsTrue()
            throws GitException, NetworkException, IOException, InterruptedException {
        createAndCommitFile("tmp.txt", "initial content");
        Files.writeString(testVault.vaultPath().resolve("tmp.txt"), "updated content");

        assertThat(gitService.hasUncommittedChanges(vault)).isTrue();
    }

    /**
     * Verifies that {@code hasUncommittedChanges()} returns {@code true} for a file
     * that has been staged but not yet committed — the key behavioural difference
     * from {@code hasChanges()}.
     */
    @Test
    void hasUncommittedChanges_withStagedNotCommitted_returnsTrue()
            throws GitException, NetworkException, IOException, InterruptedException {
        Files.writeString(testVault.vaultPath().resolve("new.txt"), "staged content");
        CommandUtil.runCommand(testVault.vaultPath().toString(),
                List.of(TestConstants.GIT_EXECUTABLE, "add", "."));

        assertThat(gitService.hasUncommittedChanges(vault)).isTrue();
    }

    // ── commitLocal() ─────────────────────────────────────────────────────────

    /**
     * Verifies that {@code commitLocal()} returns exit code {@code 0} and clears
     * the working tree when there are changes to commit.
     */
    @Test
    void commitLocal_withChanges_returnsZeroAndClearsChanges()
            throws GitException, NetworkException, IOException, InterruptedException {
        Files.writeString(testVault.vaultPath().resolve("tmp.txt"), "content");

        int exitCode = gitService.commitLocal(vault, "test commit");

        assertThat(exitCode).isEqualTo(0);
        assertThat(gitService.hasChanges(vault)).isFalse();
    }

    /**
     * Verifies that {@code commitLocal()} returns a non-zero exit code when there
     * is nothing to commit.
     */
    @Test
    void commitLocal_withNoChanges_returnsNonZero()
            throws GitException, NetworkException, InterruptedException {
        int exitCode = gitService.commitLocal(vault, "empty commit");

        assertThat(exitCode).isNotEqualTo(0);
    }

    // ── stash() / stashPop() ──────────────────────────────────────────────────

    /**
     * Verifies the stash round-trip: stash hides uncommitted changes,
     * stash pop restores them.
     */
    @Test
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

    /**
     * Verifies that stashing on a clean working tree does not throw — the guard
     * in {@link io.aledep10.nomadsync.orchestrator.SyncOrchestrator} prevents
     * this in production, but {@link GitService} must not crash if called directly.
     */
    @Test
    void stash_onCleanTree_doesNotThrow()
            throws GitException, NetworkException, InterruptedException {
        gitService.stash(vault);
    }

    // ── bootstrapVault() ──────────────────────────────────────────────────────

    /**
     * Verifies that {@code bootstrapVault()} completes without throwing when
     * no per-vault credentials are set and no global credentials are in properties.
     *
     * <p>When all credential fields are {@code null}, the method is expected to
     * be a no-op — no Git config commands are executed, no exception is thrown.</p>
     */
    @Test
    void bootstrapVault_noCredentials_doesNotThrow()
            throws GitException, InterruptedException {
        Vault noCredVault = new Vault(UUID.randomUUID().toString(),
                "AleDeP10", "no-cred-vault", testVault.vaultPath().toString());

        gitService.bootstrapVault(noCredVault);
        // no exception = pass
    }

    /**
     * Verifies that {@code bootstrapVault()} writes {@code user.name} to the vault's
     * local {@code .git/config} when a per-vault {@code gitName} is set.
     */
    @Test
    void bootstrapVault_withVaultGitName_setsLocalUserName()
            throws GitException, NetworkException, IOException, InterruptedException {
        Vault named = new Vault(UUID.randomUUID().toString(),
                "AleDeP10", "named-vault", testVault.vaultPath().toString(),
                "Vault User", null, null, null, null, null);

        gitService.bootstrapVault(named);

        String result = CommandUtil.runCommandWithOutput(testVault.vaultPath().toString(),
                List.of(TestConstants.GIT_EXECUTABLE, "config", "user.name"));
        assertThat(result.trim()).isEqualTo("Vault User");
    }

    /**
     * Verifies that {@code bootstrapVault()} falls back to the global {@code git.name}
     * property when no per-vault {@code gitName} is set.
     */
    @Test
    void bootstrapVault_vaultNameNull_fallsBackToGlobalProperty()
            throws GitException, NetworkException, IOException, InterruptedException {
        Properties props = new Properties();
        props.setProperty("git.executable", TestConstants.GIT_EXECUTABLE);
        props.setProperty("git.name", "Global User");
        GitService gs = new GitService(props, mock(VaultService.class), logService);

        Vault noNameVault = new Vault(UUID.randomUUID().toString(),
                "AleDeP10", "fallback-vault", testVault.vaultPath().toString());

        gs.bootstrapVault(noNameVault);

        String result = CommandUtil.runCommandWithOutput(testVault.vaultPath().toString(),
                List.of(TestConstants.GIT_EXECUTABLE, "config", "user.name"));
        assertThat(result.trim()).isEqualTo("Global User");
    }

    /**
     * Verifies that per-vault {@code gitName} takes precedence over
     * the global {@code git.name} property.
     */
    @Test
    void bootstrapVault_vaultNameOverridesGlobal()
            throws GitException, NetworkException, IOException, InterruptedException {
        Properties props = new Properties();
        props.setProperty("git.executable", TestConstants.GIT_EXECUTABLE);
        props.setProperty("git.name", "Global User");
        GitService gs = new GitService(props, mock(VaultService.class), logService);

        Vault overrideVault = new Vault(UUID.randomUUID().toString(),
                "AleDeP10", "override-vault", testVault.vaultPath().toString(),
                "Per-Vault User", null, null, null, null, null);

        gs.bootstrapVault(overrideVault);

        String result = CommandUtil.runCommandWithOutput(testVault.vaultPath().toString(),
                List.of(TestConstants.GIT_EXECUTABLE, "config", "user.name"));
        assertThat(result.trim()).isEqualTo("Per-Vault User");
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