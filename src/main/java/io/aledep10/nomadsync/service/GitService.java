package io.aledep10.nomadSync.service;

import io.aledep10.nomadSync.util.CommandUtil;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

/**
 * Wraps Git CLI operations via {@link ProcessBuilder}.
 *
 * <p>Each public method maps to a single Git command or a minimal guard check.
 * Sequencing and error handling are the caller's responsibility
 * ({@link io.aledep10.nomadSync.orchestrator.SyncOrchestrator}).</p>
 *
 * <p>All operations run in the vault directory specified by {@code vault.path}.</p>
 */
public class GitService {

    private final String gitExecutable;
    private final File vaultPath;
    private final LogService logService;

    /**
     * Constructs a GitService from the provided configuration.
     *
     * @param properties application properties containing {@code git.executable}
     *                   and {@code vault.path}
     * @param logService shared logging service
     */
    public GitService(Properties properties, LogService logService) {
        this.gitExecutable = properties.getProperty("git.executable");
        this.vaultPath     = new File(properties.getProperty("vault.path"));
        this.logService    = logService;
    }

    /**
     * Pushes committed changes to the remote repository.
     *
     * <p>Assumes a prior {@link #commitLocal(String)} has been performed.
     * Does not stage or commit — push only.</p>
     */
    public void push() throws IOException, InterruptedException {
        CommandUtil.runCommand(vaultPath, List.of(gitExecutable, "push"), logService);
    }

    /**
     * Pulls the latest changes from the remote repository,
     * preferring the remote version on conflicts ({@code -X theirs}).
     *
     * <p>Should be preceded by {@link #stash()} if
     * {@link #hasUncommittedChanges()} returns {@code true},
     * and followed by {@link #stashPop()} accordingly.</p>
     */
    public void pull() throws IOException, InterruptedException {
        CommandUtil.runCommand(vaultPath, List.of(gitExecutable, "pull", "-X", "theirs"), logService);
    }

    /**
     * Shelves uncommitted local changes via {@code git stash}.
     *
     * <p>Must be called only if {@link #hasUncommittedChanges()} returns {@code true}.
     * Calling stash on a clean working tree is a no-op but should be avoided
     * to prevent a mismatched {@link #stashPop()} call.</p>
     */
    public void stash() throws IOException, InterruptedException {
        CommandUtil.runCommand(vaultPath, List.of(gitExecutable, "stash"), logService);
    }

    /**
     * Restores shelved changes via {@code git stash pop}.
     *
     * <p>Must be called only if a prior {@link #stash()} was performed.
     * Calling stash pop on an empty stash returns exit code 1.</p>
     */
    public void stashPop() throws IOException, InterruptedException {
        CommandUtil.runCommand(vaultPath, List.of(gitExecutable, "stash", "pop"), logService);
    }

    /**
     * Stages all changes and commits locally with the given message.
     *
     * <p>Executes {@code git add -A} followed by {@code git commit}.
     * Does not push to remote.</p>
     *
     * @param message the commit message
     * @return exit code — {@code 0} on success, non-zero if nothing to commit
     */
    public int commitLocal(String message) throws IOException, InterruptedException {
        CommandUtil.runCommand(vaultPath, List.of(gitExecutable, "add", "-A"), logService);
        return CommandUtil.runCommand(vaultPath, List.of(gitExecutable, "commit", "-m", message), logService);
    }

    /**
     * Returns {@code true} if there are unstaged modifications in tracked files.
     *
     * <p>Uses {@code git diff --quiet}: exit code {@code 0} means no unstaged changes,
     * exit code {@code 1} means unstaged changes are present.</p>
     *
     * <p>Note: does not detect untracked files or staged-but-uncommitted changes.
     * Use {@link #hasUncommittedChanges()} for a broader check.</p>
     *
     * @return {@code true} if unstaged changes are detected
     */
    public boolean hasChanges() throws IOException, InterruptedException {
        return CommandUtil.runCommand(vaultPath, List.of(gitExecutable, "diff", "--quiet"), logService) != 0;
    }

    /**
     * Returns {@code true} if the working tree contains any uncommitted changes —
     * staged or unstaged, tracked or untracked.
     *
     * <p>Uses {@code git status --porcelain}: produces stable, locale-independent output.
     * Empty output means a clean working tree; any output means changes are present.</p>
     *
     * <p>Used as a guard before {@link #stash()} and {@link #stashPop()} to avoid
     * calling stash pop on an empty stash.</p>
     *
     * @return {@code true} if any uncommitted changes are present
     */
    public boolean hasUncommittedChanges() throws IOException, InterruptedException {
        return !CommandUtil.runCommandWithOutput(vaultPath, List.of(gitExecutable, "status", "--porcelain")).isEmpty();
    }

}