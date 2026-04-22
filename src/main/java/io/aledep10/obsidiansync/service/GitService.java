package io.aledep10.obsidiansync.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Properties;

/**
 * Wraps Git CLI operations via {@link ProcessBuilder}.
 *
 * <p>Each public method maps to a logical Git workflow (push, pull, autosave check).
 * Low-level process execution is delegated to {@link #runCommand(List)}, which captures
 * stdout/stderr and returns the exit code.</p>
 *
 * <p>All operations are executed in the vault directory specified by
 * {@code vault.path} in the configuration file.</p>
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
     */
    public GitService(Properties properties, LogService logService) {
        this.gitExecutable = properties.getProperty("git.executable");
        this.vaultPath = new File(properties.getProperty("vault.path"));
        this.logService = logService;
    }

    /**
     * Pushes local changes to the remote repository.
     *
     * <p>Executes the following sequence:</p>
     * <ol>
     *   <li>{@code git add -A}</li>
     *   <li>{@code git commit -m "push <timestamp>"}</li>
     *   <li>{@code git push}</li>
     * </ol>
     *
     * <p>If {@code git commit} exits with code 1, there is nothing to commit
     * and the push is skipped.</p>
     */
    public void push() throws IOException, InterruptedException {
        runCommand(List.of(gitExecutable, "push"));
    }

    public void stash() throws IOException, InterruptedException {
        runCommand(List.of(gitExecutable, "stash"));
    }

    /**
     * Pulls the latest changes from the remote repository.
     *
     * <p>Executes the following sequence:</p>
     * <ol>
     *   <li>{@code git stash} — shelves any uncommitted local changes</li>
     *   <li>{@code git pull -X theirs} — merges remote, preferring remote on conflicts</li>
     *   <li>{@code git stash pop} — restores shelved changes on top of the updated tree</li>
     * </ol>
     */
    public void pull() throws IOException, InterruptedException {
        runCommand(List.of(gitExecutable, "pull", "-X", "theirs"));
    }

    public void stashPop() throws IOException, InterruptedException {
        runCommand(List.of(gitExecutable, "stash", "pop"));
    }

    public int commitLocal(String message) throws IOException, InterruptedException {
        runCommand(List.of(gitExecutable, "add", "-A"));

        return runCommand(List.of(
                gitExecutable, "commit", "-m",
                message
        ));
    }

    /**
     * Performs a differential autosave: commits and pushes only if changes are detected.
     *
     * <p>Uses {@code git diff --quiet} to check for modifications.
     * Exit code 0 means no changes; exit code 1 means changes are present.</p>
     */
    public boolean hasChanges() throws IOException, InterruptedException {
        return runCommand(List.of(gitExecutable, "diff", "--quiet")) != 0;
    }

    public boolean hasUncommittedChanges() throws IOException, InterruptedException {
        // --porcelain: output stabile e machine-readable
        // output vuoto = working tree pulita
        // output non vuoto = modifiche presenti (staged o unstaged)
        return !runCommandWithOutput(List.of(gitExecutable, "status", "--porcelain"))
                .isEmpty();
    }

    /**
     * Executes a Git command in the vault directory.
     *
     * <p>Stdout and stderr are printed to the console in real time.
     * The method blocks until the process terminates.</p>
     *
     * @param command the command and its arguments as a list of strings
     * @return the process exit code
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    private int runCommand(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(vaultPath);

        // Merge stderr into stdout for unified output
        pb.redirectErrorStream(true);

        // Stream output line by line while the process runs
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logService.info("[git] " + line);
            }
        }
        return process.waitFor();
    }

    private String runCommandWithOutput(List<String> command) throws IOException, InterruptedException {
        StringBuilder output = new StringBuilder();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(vaultPath);

        // Merge stderr into stdout for unified output
        pb.redirectErrorStream(true);

        // Stream output line by line while the process runs
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        return output.toString();
    }
}