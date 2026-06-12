package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.exception.GitException;
import io.aledep10.nomadsync.exception.NetworkException;
import io.aledep10.nomadsync.exception.VaultException;
import io.aledep10.nomadsync.gitignore.exception.GitignoreException;
import io.aledep10.nomadsync.util.CommandUtil;
import io.aledep10.nomadsync.orchestrator.SyncOrchestrator;
import io.aledep10.nomadsync.util.DateFormats;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Wraps Git CLI operations via {@link ProcessBuilder}.
 *
 * <p>Each public method maps to a single Git command or a minimal guard check.
 * Sequencing and error handling are the caller's responsibility
 * ({@link SyncOrchestrator}).</p>
 *
 * <p>Operations that are strictly local ({@link #stash}, {@link #commitLocal},
 * {@link #hasChanges}, {@link #hasUncommittedChanges}) wrap any unexpected
 * {@link NetworkException} as {@link GitException} — a network error on a local
 * operation indicates an unexpected condition, not a transient connectivity failure.</p>
 *
 * <p>All operations run in the vault directory passed as {@code vaultPath}.</p>
 *
 * <h2>Constructor argument order</h2>
 * <p>Follows the project convention: {@link Properties} first, dependencies in
 * descending order of complexity, {@link LogService} last.</p>
 */
public class GitService {

    private final String gitExecutable;
    private final VaultService vaultService;
    private final LogService logService;

    /**
     * Constructs the service from the provided configuration.
     *
     * @param properties   application properties containing {@code git.executable}
     * @param vaultService vault lifecycle service — used for snapshot creation and
     *                     conflict file persistence in {@link #synchronize(String)}
     * @param logService   shared logging service
     */
    public GitService(Properties properties, VaultService vaultService, LogService logService) {
        this.gitExecutable = properties.getProperty("git.executable");
        this.vaultService  = vaultService;
        this.logService    = logService;
    }

    // ── Remote operations ─────────────────────────────────────────────────────

    /**
     * Pushes committed changes to the remote repository.
     *
     * @param vaultPath absolute path to the vault directory
     * @throws NetworkException     if a network connectivity failure is detected
     * @throws GitException         if a local Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void push(String vaultPath) throws GitException, NetworkException, InterruptedException {
        CommandUtil.runCommand(vaultPath, List.of(gitExecutable, "push"), logService);
    }

    /**
     * Pulls the latest changes from the remote repository,
     * preferring the remote version on conflicts ({@code -X theirs}).
     *
     * @param vaultPath absolute path to the vault directory
     * @throws NetworkException     if a network connectivity failure is detected
     * @throws GitException         if a local Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void pull(String vaultPath) throws GitException, NetworkException, InterruptedException {
        logService.info("Pulling vault: " + vaultPath);
        CommandUtil.runCommand(vaultPath, List.of(gitExecutable, "pull", "-X", "theirs"), logService);
    }

    // ── Local operations ──────────────────────────────────────────────────────

    /**
     * Shelves uncommitted local changes via {@code git stash}.
     *
     * @param vaultPath absolute path to the vault directory
     * @throws GitException         if a Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void stash(String vaultPath) throws GitException, InterruptedException {
        try {
            CommandUtil.runCommand(vaultPath, List.of(gitExecutable, "stash"), logService);
        } catch (NetworkException e) {
            throw new GitException("Unexpected network error on local stash operation", e);
        }
    }

    /**
     * Restores shelved changes via {@code git stash pop}.
     *
     * @param vaultPath absolute path to the vault directory
     * @throws GitException         if a Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void stashPop(String vaultPath) throws GitException, InterruptedException {
        try {
            CommandUtil.runCommand(vaultPath, List.of(gitExecutable, "stash", "pop"), logService);
        } catch (NetworkException e) {
            throw new GitException("Unexpected network error on local stash pop operation", e);
        }
    }

    /**
     * Stages all changes and commits locally with the given message.
     *
     * @param vaultPath absolute path to the vault directory
     * @param message   the commit message
     * @return exit code — {@code 0} on success, non-zero if nothing to commit
     * @throws GitException         if a Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public int commitLocal(String vaultPath, String message) throws GitException, InterruptedException {
        try {
            CommandUtil.runCommand(vaultPath, List.of(gitExecutable, "add", "-A"), logService);
            return CommandUtil.runCommand(vaultPath,
                    List.of(gitExecutable, "commit", "-m", message), logService);
        } catch (NetworkException e) {
            throw new GitException("Unexpected network error on local commit operation", e);
        }
    }

    /**
     * Returns {@code true} if there are unstaged modifications in tracked files.
     *
     * <p>Uses {@code git diff --quiet} — exit code {@code 1} means unstaged changes.</p>
     *
     * @param vaultPath absolute path to the vault directory
     * @throws GitException         if a Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean hasChanges(String vaultPath) throws GitException, InterruptedException {
        try {
            return CommandUtil.runCommand(vaultPath,
                    List.of(gitExecutable, "diff", "--quiet"), logService) != 0;
        } catch (NetworkException e) {
            throw new GitException("Unexpected network error on local diff operation", e);
        }
    }

    /**
     * Returns {@code true} if the working tree contains any uncommitted changes —
     * staged or unstaged, tracked or untracked.
     *
     * <p>Uses {@code git status --porcelain} — empty output means clean tree.</p>
     *
     * @param vaultPath absolute path to the vault directory
     * @throws GitException         if a Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean hasUncommittedChanges(String vaultPath) throws GitException, InterruptedException {
        try {
            return !CommandUtil.runCommandWithOutput(vaultPath,
                    List.of(gitExecutable, "status", "--porcelain")).isEmpty();
        } catch (NetworkException e) {
            throw new GitException("Unexpected network error on local status operation", e);
        }
    }

    // ── Synchronize ───────────────────────────────────────────────────────────

    /**
     * Executes the full bidirectional synchronisation workflow for the given vault.
     *
     * <h2>Sequence</h2>
     * <ol>
     *   <li>Commit local changes (if any).</li>
     *   <li>{@code git pull} — if successful → push → done.</li>
     *   <li>On conflict:
     *     <ol>
     *       <li>{@code git merge --abort} (exit code ignored).</li>
     *       <li>FIFO snapshot via {@link VaultService#makeVaultSnapshot(String)}.</li>
     *       <li>{@code git pull -X ours --no-edit}.</li>
     *       <li>Extract conflicted files from {@code "Auto-merging"} lines in stdout.</li>
     *       <li>For each conflicted file: fetch remote version via
     *           {@link CommandUtil#runCommandToFile} to a temp file, then move to
     *           {@code conflictsRoot} via {@link VaultService#saveConflict}.</li>
     *       <li>Push.</li>
     *     </ol>
     *   </li>
     * </ol>
     *
     * <p>{@link GitignoreException} from the snapshot step is wrapped as
     * {@link GitException} — the orchestrator handles a single exception hierarchy
     * and applies its retry + backoff strategy uniformly.</p>
     *
     * @param vaultPath absolute path to the vault directory
     * @return list of relative paths of conflicted files, empty if no conflict
     * @throws GitException         if an unrecoverable Git error occurs
     * @throws NetworkException     if a network connectivity failure is detected
     * @throws VaultException       if snapshot or conflict persistence fails
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public List<String> synchronize(String vaultPath)
            throws GitException, NetworkException, VaultException, InterruptedException {
        logService.info("Synchronizing vault: " + vaultPath);
        List<String> result = new ArrayList<>();

        try {
            String timestamp   = DateFormats.nowLog();
            int commitExitCode = commitLocal(vaultPath, timestamp + ", synchronize");
            logService.debug("Commit exit code: " + commitExitCode);

            StringWriter sw1 = new StringWriter();
            int pullExitCode = CommandUtil.runCommandToWriter(
                    vaultPath, List.of(gitExecutable, "pull"), new PrintWriter(sw1));
            String pullOutput = sw1.toString();
            logService.debug("Pull exit code: " + pullExitCode);
            logService.debug(pullOutput);

            if (pullExitCode == 0) {
                int pushExitCode = CommandUtil.runCommand(
                        vaultPath, List.of(gitExecutable, "push"), logService);
                if (pushExitCode != 0) {
                    logService.warn("git push failed with code " + pushExitCode);
                }
            } else {
                // abort any in-progress merge — non-zero exit is normal if no merge was running
                CommandUtil.runCommand(
                        vaultPath, List.of(gitExecutable, "merge", "--abort"), logService);

                try {
                    vaultService.makeVaultSnapshot(vaultPath);
                } catch (GitignoreException e) {
                    throw new GitException("Snapshot failed — gitignore read error", e);
                }

                StringWriter sw2 = new StringWriter();
                int oursExitCode = CommandUtil.runCommandToWriter(
                        vaultPath, List.of(gitExecutable, "pull", "-X", "ours", "--no-edit"),
                        new PrintWriter(sw2));
                String oursOutput = sw2.toString();
                logService.debug("Ours exit code: " + oursExitCode);
                logService.debug(oursOutput);

                List<String> conflicted = oursOutput.lines()
                        .filter(l -> l.startsWith("Auto-merging"))
                        .map(l -> l.replace("Auto-merging ", "").trim())
                        .toList();

                String vaultName       = Path.of(vaultPath).getFileName().toString();
                String conflictDirName = vaultName + "_" + DateFormats.nowLog();

                for (String file : conflicted) {
                    String basename = Path.of(file).getFileName().toString();
                    Path tempFile   = Files.createTempFile("nomadsync-conflict-", "-" + basename);
                    try {
                        int showExitCode = CommandUtil.runCommandToFile(
                                vaultPath,
                                List.of(gitExecutable, "--no-pager", "show", "FETCH_HEAD:" + file),
                                tempFile);
                        logService.debug("show " + file + " exit code: " + showExitCode);
                        vaultService.saveConflict(conflictDirName, basename, tempFile);
                    } finally {
                        Files.deleteIfExists(tempFile); // no-op if saveConflict already moved it
                    }
                    result.add(file);
                }

                int pushExitCode = CommandUtil.runCommand(
                        vaultPath, List.of(gitExecutable, "push"), logService);
                if (pushExitCode != 0) {
                    logService.warn("git push failed with code " + pushExitCode);
                }
            }
            return result;

        } catch (IOException e) {
            throw new VaultException("Unable to handle conflicts", e);
        }
    }
}