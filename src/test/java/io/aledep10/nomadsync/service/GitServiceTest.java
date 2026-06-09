package io.aledep10.nomadSync.service;

import io.aledep10.nomadSync.util.CommandUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Integration tests for {@link GitService}.
 *
 * <p>Each test runs against a real Git repository created in a temporary directory.
 * The directory is initialised before each test and deleted after, guaranteeing
 * full isolation between test cases.</p>
 *
 * <p>{@link LogService} is shared across all tests ({@code @BeforeAll}) since it
 * carries no mutable state relevant to Git operations.</p>
 */
class GitServiceTest {

    static final String GIT_EXECUTABLE = "git";

    static LogService logService;

    File tempDir;
    GitService gitService;

    // ── Shared setup ─────────────────────────────────────────────────────────

    @BeforeAll
    static void prepareLogService() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("log.path",  System.getProperty("java.io.tmpdir") + "/nomadSync-test.log");
        properties.setProperty("log.level", "DEBUG");
        logService = new LogService(properties);
    }

    // ── Per-test setup / teardown ─────────────────────────────────────────────

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        tempDir = Files.createTempDirectory("nomadSync-test-").toFile();

        CommandUtil.runCommand(tempDir, List.of(GIT_EXECUTABLE, "init"));
        CommandUtil.runCommand(tempDir, List.of(GIT_EXECUTABLE, "config", "user.name",  "Test User"));
        CommandUtil.runCommand(tempDir, List.of(GIT_EXECUTABLE, "config", "user.email", "test@test.com"));

        // use --local to avoid polluting global git config
        Properties properties = new Properties();
        properties.setProperty("git.executable", GIT_EXECUTABLE);
        properties.setProperty("vault.path", tempDir.getAbsolutePath());
        gitService = new GitService(properties, logService);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Java recursive delete — ProcessBuilder cannot run shell built-ins like Remove-Item
        Files.walk(tempDir.toPath())
                .sorted(Comparator.reverseOrder())  // files before their parent directories
                .map(Path::toFile)
                .forEach(File::delete);
    }

    // ── hasChanges() ──────────────────────────────────────────────────────────

    @Test
    void hasChanges_noChanges_returnsFalse() throws IOException, InterruptedException {
        createAndCommitFile("tmp.txt", "initial content");

        assertThat(gitService.hasChanges()).isFalse();
    }

    @Test
    void hasChanges_withUnstagedModification_returnsTrue() throws IOException, InterruptedException {
        createAndCommitFile("tmp.txt", "initial content");

        // modify without staging — only git diff detects this
        Files.writeString(tempDir.toPath().resolve("tmp.txt"), "updated content");

        assertThat(gitService.hasChanges()).isTrue();
    }

    // ── hasUncommittedChanges() ───────────────────────────────────────────────

    @Test
    void hasUncommittedChanges_cleanTree_returnsFalse() throws IOException, InterruptedException {
        createAndCommitFile("tmp.txt", "initial content");

        assertThat(gitService.hasUncommittedChanges()).isFalse();
    }

    @Test
    void hasUncommittedChanges_withUnstagedModification_returnsTrue() throws IOException, InterruptedException {
        createAndCommitFile("tmp.txt", "initial content");

        Files.writeString(tempDir.toPath().resolve("tmp.txt"), "updated content");

        assertThat(gitService.hasUncommittedChanges()).isTrue();
    }

    @Test
    void hasUncommittedChanges_withStagedNotCommitted_returnsTrue() throws IOException, InterruptedException {
        // this case distinguishes hasUncommittedChanges() from hasChanges()
        // a staged file is invisible to git diff but visible to git status --porcelain
        Files.writeString(tempDir.toPath().resolve("new.txt"), "staged content");
        CommandUtil.runCommand(tempDir, List.of(GIT_EXECUTABLE, "add", "."));

        assertThat(gitService.hasUncommittedChanges()).isTrue();
    }

    // ── commitLocal() ─────────────────────────────────────────────────────────

    @Test
    void commitLocal_withChanges_returnsZeroAndClearsChanges() throws IOException, InterruptedException {
        Files.writeString(tempDir.toPath().resolve("tmp.txt"), "content");

        int exitCode = gitService.commitLocal("test commit");

        assertThat(exitCode).isEqualTo(0);
        assertThat(gitService.hasChanges()).isFalse();
    }

    @Test
    void commitLocal_withNoChanges_returnsNonZero() throws IOException, InterruptedException {
        // empty repo — nothing to commit
        int exitCode = gitService.commitLocal("empty commit");

        assertThat(exitCode).isNotEqualTo(0);
    }

    // ── stash() / stashPop() ──────────────────────────────────────────────────

    @Test
    void stash_shelvesThenPopRestores() throws IOException, InterruptedException {
        createAndCommitFile("tmp.txt", "initial content");
        Files.writeString(tempDir.toPath().resolve("tmp.txt"), "modified content");

        assertThat(gitService.hasUncommittedChanges()).isTrue();

        gitService.stash();
        assertThat(gitService.hasUncommittedChanges()).isFalse();

        gitService.stashPop();
        assertThat(gitService.hasUncommittedChanges()).isTrue();
    }

    @Test
    void stash_onCleanTree_doesNotThrow() throws IOException, InterruptedException {
        // guard hasUncommittedChanges() in SyncOrchestrator prevents this in production
        // here we verify that GitService itself does not throw on an empty stash
        gitService.stash();
        // no assertion needed — test passes if no exception is thrown
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a file with the given content in the temp repo and commits it.
     * Used to bring the repo into a known clean state before each scenario.
     */
    private void createAndCommitFile(String filename, String content)
            throws IOException, InterruptedException {
        Files.writeString(tempDir.toPath().resolve(filename), content);
        CommandUtil.runCommand(tempDir, List.of(GIT_EXECUTABLE, "add", "."));
        CommandUtil.runCommand(tempDir, List.of(GIT_EXECUTABLE, "commit", "-m", "test: initial commit"));
    }
}