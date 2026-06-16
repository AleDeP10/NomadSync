package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.exception.GitException;
import io.aledep10.nomadsync.exception.NetworkException;
import io.aledep10.nomadsync.exception.VaultException;
import io.aledep10.nomadsync.gitignore.exception.GitignoreException;
import io.aledep10.nomadsync.orchestrator.SyncOrchestrator;
import io.aledep10.nomadsync.orchestrator.Vault;
import io.aledep10.nomadsync.util.CommandUtil;
import io.aledep10.nomadsync.util.DateFormats;
import io.aledep10.nomadsync.util.StringUtil;

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
 * <p>All methods receive a {@link Vault} instead of a raw path string —
 * this gives each method access to per-vault Git configuration ({@code gitBranch},
 * {@code gitRemote}) and credentials ({@code gitName}, {@code gitEmail},
 * {@code gitToken}) alongside the vault path, without requiring additional
 * parameters.</p>
 *
 * <p>Operations that are strictly local ({@link #stash}, {@link #stashPop},
 * {@link #commitLocal}, {@link #hasChanges}, {@link #hasUncommittedChanges})
 * wrap any unexpected {@link NetworkException} as {@link GitException} — a network
 * error on a local operation indicates an unexpected condition, not a transient
 * connectivity failure.</p>
 *
 * <h2>Credential bootstrap</h2>
 * <p>{@link #bootstrapVault(Vault)} must be called once per vault at process
 * startup (and re-called whenever credentials change via {@code NomadSync config}).
 * It writes per-vault {@code user.name}, {@code user.email}, and the authenticated
 * remote URL to the vault's local {@code .git/config}, ensuring all subsequent
 * Git operations use the correct identity and authentication without relying on
 * global system configuration.</p>
 *
 * <h2>Constructor argument order</h2>
 * <p>Follows the project convention: {@link Properties} first, dependencies in
 * descending order of complexity, {@link LogService} last.</p>
 */
public class GitService {

    private final Properties properties;
    private final String gitExecutable;
    private final VaultService vaultService;
    private final LogService logService;

    /**
     * Constructs the service from the provided configuration.
     *
     * @param properties   application properties — must contain {@code git.executable};
     *                     may contain global Git credential defaults ({@code git.name},
     *                     {@code git.email}, {@code git.username}, {@code git.token},
     *                     {@code git.remote}, {@code git.branch})
     * @param vaultService vault lifecycle service — used for snapshot creation and
     *                     conflict file persistence in {@link #synchronize(Vault)}
     * @param logService   shared logging service
     */
    public GitService(Properties properties, VaultService vaultService, LogService logService) {
        this.properties    = properties;
        this.gitExecutable = properties.getProperty("git.executable", "git");
        this.vaultService  = vaultService;
        this.logService    = logService;
    }

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    /**
     * Applies per-vault Git identity and authentication to the vault's local
     * {@code .git/config}.
     *
     * <p>Must be called once per vault at process startup — before any Git
     * operation is performed on that vault. Re-call whenever vault credentials
     * change (e.g. via {@code NomadSync config --vault=... --git.token=...})
     * to apply the new values immediately without restarting the process.</p>
     *
     * <h2>Credential resolution order</h2>
     * <p>For each credential field: per-vault value (from {@link Vault}) →
     * global value (from {@code config.properties}) → system default
     * ({@code ~/.gitconfig} — no action taken, Git handles it automatically).</p>
     *
     * <h2>Remote URL</h2>
     * <p>If a token is available (per-vault or global), the remote URL is set to
     * {@code https://<username>@github.com/<owner>/<name>} with the token embedded
     * via the {@code credential.helper} approach. This is cross-platform and does
     * not depend on any shell-specific syntax or OS keychain integration.</p>
     *
     * @param vault the vault to configure
     * @throws GitException         if a Git config command fails
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void bootstrapVault(Vault vault) throws GitException, InterruptedException {
        String path = vault.getPath();

        String name  = StringUtil.coalesce(vault.getGitName(),
                properties.getProperty("git.name"));
        String email = StringUtil.coalesce(vault.getGitEmail(),
                properties.getProperty("git.email"));

        try {
            if (name != null) {
                CommandUtil.runCommand(path,
                        List.of(gitExecutable, "config", "user.name", name), logService);
            }
            if (email != null) {
                CommandUtil.runCommand(path,
                        List.of(gitExecutable, "config", "user.email", email), logService);
            }

            String token    = StringUtil.coalesce(vault.getGitToken(),
                    properties.getProperty("git.token"));
            String username = StringUtil.coalesce(vault.getGitUsername(),
                    properties.getProperty("git.username"));
            String remote   = StringUtil.coalesce(vault.getGitRemote(),
                    properties.getProperty("git.remote", "origin"));

            if (token != null && username != null) {
                String remoteUrl = "https://" + token + "@github.com/"
                        + vault.getOwner() + "/" + vault.getName();
                CommandUtil.runCommand(path,
                        List.of(gitExecutable, "remote", "set-url", remote, remoteUrl),
                        logService);
            }

            logService.info("bootstrapVault: configured " + vault.getRepoSlug());

        } catch (NetworkException e) {
            throw new GitException("Unexpected network error during vault bootstrap", e);
        }
    }

    // ── Remote operations ─────────────────────────────────────────────────────

    /**
     * Pushes committed changes to the remote repository.
     *
     * @param vault the vault to push
     * @throws NetworkException     if a network connectivity failure is detected
     * @throws GitException         if a local Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void push(Vault vault) throws GitException, NetworkException, InterruptedException {
        String remote = StringUtil.coalesce(vault.getGitRemote(),
                properties.getProperty("git.remote", "origin"));
        String branch = StringUtil.coalesce(vault.getGitBranch(),
                properties.getProperty("git.branch", "main"));
        CommandUtil.runCommand(vault.getPath(),
                List.of(gitExecutable, "push", remote, branch), logService);
    }

    /**
     * Pulls the latest changes from the remote repository,
     * preferring the remote version on conflicts ({@code -X theirs}).
     *
     * @param vault the vault to pull
     * @throws NetworkException     if a network connectivity failure is detected
     * @throws GitException         if a local Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void pull(Vault vault) throws GitException, NetworkException, InterruptedException {
        logService.info("Pulling vault: " + vault.getRepoSlug());
        CommandUtil.runCommand(vault.getPath(),
                List.of(gitExecutable, "pull", "-X", "theirs"), logService);
    }

    // ── Local operations ──────────────────────────────────────────────────────

    /**
     * Shelves uncommitted local changes via {@code git stash}.
     *
     * @param vault the vault to stash
     * @throws GitException         if a Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void stash(Vault vault) throws GitException, InterruptedException {
        try {
            CommandUtil.runCommand(vault.getPath(),
                    List.of(gitExecutable, "stash"), logService);
        } catch (NetworkException e) {
            throw new GitException("Unexpected network error on local stash operation", e);
        }
    }

    /**
     * Restores shelved changes via {@code git stash pop}.
     *
     * @param vault the vault to restore
     * @throws GitException         if a Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void stashPop(Vault vault) throws GitException, InterruptedException {
        try {
            CommandUtil.runCommand(vault.getPath(),
                    List.of(gitExecutable, "stash", "pop"), logService);
        } catch (NetworkException e) {
            throw new GitException("Unexpected network error on local stash pop operation", e);
        }
    }

    /**
     * Stages all changes and commits locally with the given message.
     *
     * @param vault   the vault to commit in
     * @param message the commit message
     * @return exit code — {@code 0} on success, non-zero if nothing to commit
     * @throws GitException         if a Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public int commitLocal(Vault vault, String message) throws GitException, InterruptedException {
        try {
            CommandUtil.runCommand(vault.getPath(),
                    List.of(gitExecutable, "add", "-A"), logService);
            return CommandUtil.runCommand(vault.getPath(),
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
     * @param vault the vault to check
     * @throws GitException         if a Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean hasChanges(Vault vault) throws GitException, InterruptedException {
        try {
            return CommandUtil.runCommand(vault.getPath(),
                    List.of(gitExecutable, "diff", "--quiet"), logService) != 0;
        } catch (NetworkException e) {
            throw new GitException("Unexpected network error on local diff operation", e);
        }
    }

    /**
     * Returns {@code true} if the working tree contains any uncommitted changes —
     * staged or unstaged, tracked or untracked.
     *
     * <p>Uses {@code git status --porcelain} — empty output means clean tree.
     * This is more thorough than {@link #hasChanges(Vault)}, which only checks
     * unstaged modifications to tracked files.</p>
     *
     * @param vault the vault to check
     * @throws GitException         if a Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean hasUncommittedChanges(Vault vault) throws GitException, InterruptedException {
        try {
            return !CommandUtil.runCommandWithOutput(vault.getPath(),
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
     *       <li>{@code git merge --abort} (exit code ignored — may be non-zero
     *           if no merge was in progress).</li>
     *       <li>FIFO snapshot via {@link VaultService#makeVaultSnapshot(String)}.</li>
     *       <li>{@code git pull -X ours --no-edit} — local version wins.</li>
     *       <li>Parse {@code "Auto-merging"} lines from stdout to identify
     *           conflicted files.</li>
     *       <li>For each conflicted file: extract the remote version via
     *           {@link CommandUtil#runCommandToFile} ({@code git show FETCH_HEAD:<file>})
     *           to a temp file, then persist via {@link VaultService#saveConflict}.</li>
     *       <li>Push.</li>
     *     </ol>
     *   </li>
     * </ol>
     *
     * <h2>Snapshot and conflict directory naming</h2>
     * <p>Both use {@link Vault#getRepoSlug()} with {@code /} replaced by {@code _}
     * as the prefix — e.g. {@code AleDeP10_public-vault_2026-06-16_10-30}.
     * This avoids collisions between two vaults with the same {@code name} but
     * different {@code owner}s sharing the same {@code backupsRoot} or
     * {@code conflictsRoot}.</p>
     *
     * <h2>Exception wrapping</h2>
     * <p>{@link GitignoreException} from the snapshot step is wrapped as
     * {@link GitException} — the orchestrator handles a single exception hierarchy
     * and applies its retry + backoff strategy uniformly.</p>
     *
     * @param vault the vault to synchronise
     * @return list of relative paths of conflicted files, empty if no conflict occurred
     * @throws GitException         if an unrecoverable Git error occurs
     * @throws NetworkException     if a network connectivity failure is detected
     * @throws VaultException       if snapshot or conflict persistence fails
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public List<String> synchronize(Vault vault)
            throws GitException, NetworkException, VaultException, InterruptedException {
        String vaultPath      = vault.getPath();
        String snapshotPrefix = vault.getRepoSlug().replace("/", "_");
        logService.info("Synchronizing vault: " + vault.getRepoSlug());
        List<String> result = new ArrayList<>();

        try {
            String timestamp   = DateFormats.nowLog();
            int commitExitCode = commitLocal(vault, timestamp + ", synchronize");
            logService.debug("Commit exit code: " + commitExitCode);

            StringWriter sw1 = new StringWriter();
            int pullExitCode = CommandUtil.runCommandToWriter(
                    vaultPath, List.of(gitExecutable, "pull"), new PrintWriter(sw1));
            String pullOutput = sw1.toString();
            logService.debug("Pull exit code: " + pullExitCode);
            logService.debug(pullOutput);

            if (pullExitCode == 0) {
                push(vault);
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

                String conflictDirName = snapshotPrefix + "_" + DateFormats.nowLog();

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
                        Files.deleteIfExists(tempFile);
                    }
                    result.add(file);
                }

                push(vault);
            }
            return result;

        } catch (IOException e) {
            throw new VaultException("Unable to handle conflicts", e);
        }
    }
}