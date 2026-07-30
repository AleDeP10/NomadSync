package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.config.NomadProperties;
import io.aledep10.nomadsync.exception.GitException;
import io.aledep10.nomadsync.exception.NetworkException;
import io.aledep10.nomadsync.exception.VaultException;
import io.aledep10.nomadsync.gitignore.exception.GitignoreException;
import io.aledep10.nomadsync.orchestrator.SyncOrchestrator;
import io.aledep10.nomadsync.vault.Vault;
import io.aledep10.nomadsync.util.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps Git CLI operations via {@link ProcessBuilder}.
 *
 * <p>Each public method maps to a single Git command or a minimal guard check.
 * Sequencing and error handling are the caller's responsibility — either
 * {@link SyncOrchestrator} for event-driven, per-vault workflows, or
 * {@link io.aledep10.nomadsync.Main}'s one-shot CLI handlers for direct
 * invocations (e.g. {@code status}, {@code vault show}, {@code vault create}).</p>
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
 * <h2>Authentication strategy</h2>
 * <p>Credentials are applied <em>once</em> at bootstrap via
 * {@link #bootstrapVault(Vault)}, which writes {@code user.name},
 * {@code user.email}, and an authenticated remote URL
 * ({@code https://<token>@github.com/<owner>/<name>}) into the vault's local
 * {@code .git/config}. All subsequent remote operations ({@link #pull},
 * {@link #push}) use plain {@code git pull} / {@code git push} with no
 * credential arguments — Git reads the token from the already-configured URL.</p>
 *
 * <p>This approach is cross-platform, does not depend on any OS credential
 * manager, and ensures the token never appears in log output (it is written
 * to {@code .git/config}, not passed as a command-line argument).</p>
 *
 * <h2>Constructor argument order</h2>
 * <p>Follows the project convention: {@link Properties} first, dependencies in
 * descending order of complexity, {@link LogService} last.</p>
 *
 * <h2>Logging conventions</h2>
 * <p>A single {@code INFO}-level log line is emitted at the <em>start</em> of
 * every mutating operation (one that changes local or remote repository state) —
 * identifying the vault and the action about to be performed. The specific
 * outcome of that action (e.g. whether {@code init} was a no-op, which remote
 * sub-command {@code bootstrapVault} chose) is logged at {@code DEBUG} level,
 * not {@code INFO} — the intro line is the single point of observability for
 * "this happened", and debug detail is opt-in for troubleshooting.</p>
 * <p>Pure queries ({@link #hasChanges}, {@link #hasUncommittedChanges},
 * {@link #status}, {@link #statusShort}) emit no log at all — they answer a
 * question without changing anything, and logging every read would be noise,
 * not observability, especially for methods that may be polled frequently.</p>
 */
public class GitService {

    private final Properties       properties;
    private final String           gitExecutable;
    private final VaultService     vaultService;
    private final GitignoreService gitignoreService;
    private final LogService       logService;

    /**
     * Constructs the service from the provided configuration.
     *
     * @param properties   application properties — must contain
     *                     {@link NomadProperties.Git#EXECUTABLE};
     *                     may contain global Git credential defaults
     *                     ({@link NomadProperties.Git#NAME},
     *                     {@link NomadProperties.Git#EMAIL},
     *                     {@link NomadProperties.Git#USERNAME},
     *                     {@link NomadProperties.Git#TOKEN},
     *                     {@link NomadProperties.Git#REMOTE},
     *                     {@link NomadProperties.Git#BRANCH})
     * @param vaultService     vault lifecycle service — used for snapshot creation
     *                         and conflict file persistence in {@link #synchronize(Vault)}
     * @param gitignoreService used to normalise {@code .gitignore} immediately before
     *                         any operation that stages files ({@link #commitLocal},
     *                         {@link #stash}) — closes the window in which a manually
     *                         edited {@code .gitignore} missing a SYSTEM pattern could
     *                         let a secret slip into a commit
     * @param logService       shared logging service
     */
    public GitService(Properties properties, VaultService vaultService,
                      GitignoreService gitignoreService, LogService logService) {
        this.properties       = properties;
        this.gitExecutable    = PropertiesUtil.get(properties, NomadProperties.Git.EXECUTABLE, "git");
        this.vaultService     = vaultService;
        this.gitignoreService = gitignoreService;
        this.logService       = logService;
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
     * <p>For each field: per-vault value ({@link Vault}) → global value
     * ({@link NomadProperties.Git} in {@code config.properties}) → system default
     * ({@code ~/.gitconfig} — no action taken, Git handles it automatically).</p>
     *
     * <h2>Remote URL and token security</h2>
     * <p>If a token is available (per-vault or global), the authenticated remote
     * URL ({@code https://<token>@github.com/<owner>/<name>}) is written to the
     * vault's local {@code .git/config}. If the remote does not yet exist,
     * {@code git remote add} is used; otherwise {@code git remote set-url} updates
     * the existing entry. The token is stored only in {@code .git/config} — never
     * passed as a command-line argument and therefore never logged. {@code .git/}
     * is excluded from Git tracking by design, so the token is never committed
     * to the repository.</p>
     *
     * <p>Logging: a single {@code INFO} line announces the operation at the start;
     * which remote sub-command was chosen ({@code add} vs {@code set-url}) is
     * logged at {@code DEBUG}.</p>
     *
     * @param vault the vault to configure
     * @throws GitException         if a Git config command fails
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void bootstrapVault(Vault vault) throws GitException, InterruptedException {
        String path = vault.getPath();
        logService.info("bootstrapVault - " + vault.getRepoSlug()
                + " - configuring Git identity and remote");

        String name = vault.getGitName() != null
                ? vault.getGitName()
                : properties.getProperty(NomadProperties.Git.NAME);
        String email = vault.getGitEmail() != null
                ? vault.getGitEmail()
                : properties.getProperty(NomadProperties.Git.EMAIL);

        try {
            if (name != null) {
                CommandUtil.runCommand(path,
                        List.of(gitExecutable, "config", "user.name", name), logService);
            }
            if (email != null) {
                CommandUtil.runCommand(path,
                        List.of(gitExecutable, "config", "user.email", email), logService);
            }

            String token = vault.getGitToken() != null
                    ? vault.getGitToken()
                    : properties.getProperty(NomadProperties.Git.TOKEN);
            String username = vault.getGitUsername() != null
                    ? vault.getGitUsername()
                    : properties.getProperty(NomadProperties.Git.USERNAME);
            String remote = vault.getGitRemote() != null
                    ? vault.getGitRemote()
                    : PropertiesUtil.get(properties, NomadProperties.Git.REMOTE, "origin");

            if (token != null && username != null) {
                // Token embedded in URL — written to .git/config, never logged.
                // .git/ is never committed, so the token stays local.
                String remoteUrl = "https://" + token + "@github.com/"
                        + vault.getOwner() + "/" + vault.getName();

                // Check whether the remote already exists.
                // Use 'git remote add' if absent, 'git remote set-url' if present.
                String existingRemotes = CommandUtil.runCommandWithOutput(path,
                        List.of(gitExecutable, "remote"));
                boolean remoteExists = Arrays.asList(existingRemotes.split("\\n"))
                        .contains(remote);
                String remoteSubCmd = remoteExists ? "set-url" : "add";

                CommandUtil.runCommand(path,
                        List.of(gitExecutable, "remote", remoteSubCmd, remote, remoteUrl),
                        Set.of(remoteUrl),   // remoteUrl logged as <hidden>
                        logService);
                logService.debug("bootstrapVault - " + vault.getRepoSlug()
                        + " - remote '" + remote + "' " + remoteSubCmd);
            }

        } catch (NetworkException e) {
            throw new GitException("unexpected network error during vault bootstrap", e);
        }
    }

    // ── Repository initialization ─────────────────────────────────────────────

    /**
     * Initialises a new Git repository in the vault's local directory.
     *
     * <p>If a {@code .git/} directory already exists at the vault path, this
     * method is a no-op — {@code git init} on an existing repository is safe
     * but unnecessary.</p>
     *
     * <p>Logging: a single {@code INFO} line announces the operation at the
     * start; whether the call turned out to be a no-op or an actual
     * {@code git init} is logged at {@code DEBUG}.</p>
     *
     * @param vault the vault whose path will be initialised as a Git repository
     * @throws GitException         if {@code git init} fails
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void init(Vault vault) throws GitException, InterruptedException {
        logService.info("init - " + vault.getRepoSlug() + " - initialising repository");

        Path gitFolder = Path.of(vault.getPath(), ".git");
        if (Files.exists(gitFolder)) {
            logService.debug("init - " + vault.getRepoSlug() + " - already exists, skipped");
            return;
        }
        try {
            CommandUtil.runCommand(vault.getPath(), List.of(gitExecutable, "init"));
        } catch (NetworkException e) {
            throw new GitException("unexpected network error on local init operation", e);
        }
        logService.debug("init - " + vault.getRepoSlug() + " - git init executed");
    }

    /**
     * Discards the vault's local Git history and starts a fresh repository.
     *
     * <p>Unlike {@link #init(Vault)} — which is a no-op when {@code .git/} already
     * exists — this method unconditionally removes any existing {@code .git/}
     * directory before running {@code git init}. Intended exclusively for
     * {@code vault relocate}, where discarding history is the deliberate goal,
     * not an edge case to guard against.</p>
     *
     * <p>Logging: a single {@code INFO} line announces the operation at the start,
     * per the project's logging conventions (Rule C — intro on mutation, no outro).</p>
     *
     * @param vault the vault whose local Git history will be discarded
     * @throws GitException         if the existing {@code .git/} cannot be removed
     *                              or {@code git init} fails
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void reset(Vault vault) throws GitException, InterruptedException {
        logService.info("reset - " + vault.getRepoSlug() + " - discarding local Git history");

        Path gitFolder = Path.of(vault.getPath(), ".git");
        try {
            if (Files.exists(gitFolder)) FileUtil.deleteRecursively(gitFolder);
            CommandUtil.runCommand(vault.getPath(), List.of(gitExecutable, "init"));
        } catch (IOException e) {
            throw new GitException("failed to discard local history", e);
        } catch(NetworkException e) {
            throw new GitException("unexpected network error on local reset operation", e);
        }
    }

    // ── Remote operations ─────────────────────────────────────────────────────

    /**
     * Pushes committed changes to the remote repository.
     *
     * <p>Authentication is handled transparently by the remote URL written to
     * {@code .git/config} during {@link #bootstrapVault(Vault)} — no credential
     * arguments are passed to {@code git push}.</p>
     *
     * @param vault the vault to push
     * @throws NetworkException     if a network connectivity failure is detected
     * @throws GitException         if a local Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void push(Vault vault) throws GitException, NetworkException, InterruptedException {
        String remote = StringUtil.coalesce(vault.getGitRemote(),
                PropertiesUtil.get(properties, NomadProperties.Git.REMOTE, "origin"));
        String branch = StringUtil.coalesce(vault.getGitBranch(),
                PropertiesUtil.get(properties, NomadProperties.Git.BRANCH, "main"));
        logService.info("push - " + vault.getRepoSlug()
                + " - pushing to " + remote + "/" + branch);
        CommandUtil.runCommand(vault.getPath(),
                List.of(gitExecutable, "push", "-u", remote, branch), logService);
    }

    /**
     * Pulls the latest changes from the remote repository,
     * preferring the remote version on conflicts ({@code -X theirs}).
     *
     * <p>Authentication is handled transparently by the remote URL written to
     * {@code .git/config} during {@link #bootstrapVault(Vault)} — no credential
     * arguments are passed to {@code git pull}.</p>
     *
     * @param vault the vault to pull
     * @throws NetworkException     if a network connectivity failure is detected
     * @throws GitException         if a local Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void pull(Vault vault) throws GitException, NetworkException, InterruptedException {
        logService.info("pull - " + vault.getRepoSlug() + " - pulling from remote (theirs strategy)");
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
        logService.info("stash - " + vault.getRepoSlug() + " - stashing local changes");
        try {
            gitignoreService.load(Path.of(vault.getPath()));
        } catch (GitignoreException e) {
            throw new GitException("unable to normalise .gitignore before stash: " + e.getMessage(), e);
        }
        try {
            // git stash reverts ALL uncommitted changes to tracked files back to
            // HEAD — including the .gitignore normalization load() just wrote,
            // if it isn't committed first. Committing just .gitignore here makes
            // the fix permanent in history before stash ever touches the working
            // tree, so it survives regardless of what else gets stashed.
            commitGitignoreIfChanged(vault);
            CommandUtil.runCommand(vault.getPath(),
                    List.of(gitExecutable, "stash"), logService);
        } catch (NetworkException e) {
            throw new GitException("unexpected network error on local stash operation", e);
        }
    }

    /**
     * Commits only {@code .gitignore}, if it has uncommitted changes — used by
     * {@link #stash} to make a {@link GitignoreService#load} normalization
     * permanent before it would otherwise be reverted by {@code git stash}
     * (which restores all tracked-file changes to {@code HEAD}, not just the
     * ones the caller intended to shelve).
     *
     * <p>A non-zero exit code from the commit attempt simply means
     * {@code .gitignore} had nothing to commit — not an error.</p>
     */
    private void commitGitignoreIfChanged(Vault vault) throws GitException, InterruptedException {
        try {
            CommandUtil.runCommand(vault.getPath(),
                    List.of(gitExecutable, "add", ".gitignore"), logService);
            int exitCode = CommandUtil.runCommand(vault.getPath(),
                    List.of(gitExecutable, "commit", "-m", "nomadsync: normalise .gitignore"), logService);
            logService.debug("stash - " + vault.getRepoSlug()
                    + " - .gitignore commit exit code: " + exitCode);
        } catch (NetworkException e) {
            throw new GitException("unexpected network error while committing .gitignore before stash", e);
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
        logService.info("stashPop - " + vault.getRepoSlug() + " - restoring stashed changes");
        try {
            CommandUtil.runCommand(vault.getPath(),
                    List.of(gitExecutable, "stash", "pop"), logService);
        } catch (NetworkException e) {
            throw new GitException("unexpected network error on local stash pop operation", e);
        }
    }

    /**
     * Stages all changes and commits locally with the given message.
     *
     * <p>{@code .gitignore} is normalised via {@link GitignoreService#load} immediately
     * before staging — this closes the window in which a manually edited or corrupted
     * {@code .gitignore} (missing a SYSTEM pattern, or with an added negation) could
     * let a secret file slip into {@code git add -A}. The normalisation always
     * rewrites the file to its canonical, safe form regardless of what was found —
     * see {@link GitignoreService#load} for why this is safe to call unconditionally.</p>
     *
     * @param vault   the vault to commit in
     * @param message the commit message
     * @return exit code — {@code 0} on success, non-zero if nothing to commit
     * @throws GitException         if a Git error occurs, or if {@code .gitignore}
     *                              normalisation fails
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public int commitLocal(Vault vault, String message) throws GitException, InterruptedException {
        logService.info("commitLocal - " + vault.getRepoSlug() + " - committing local changes");
        try {
            gitignoreService.load(Path.of(vault.getPath()));
        } catch (GitignoreException e) {
            throw new GitException("unable to normalise .gitignore before commit: " + e.getMessage(), e);
        }
        try {
            CommandUtil.runCommand(vault.getPath(),
                    List.of(gitExecutable, "add", "-A"), logService);
            return CommandUtil.runCommand(vault.getPath(),
                    List.of(gitExecutable, "commit", "-m", message), logService);
        } catch (NetworkException e) {
            throw new GitException("unexpected network error on local commit operation", e);
        }
    }

    /**
     * Returns {@code true} if there are unstaged modifications in tracked files.
     *
     * <p>Uses {@code git diff --quiet} — exit code {@code 1} means unstaged changes.
     * Pure query — emits no log, per the project's logging conventions.</p>
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
            throw new GitException("unexpected network error on local diff operation", e);
        }
    }

    /**
     * Returns {@code true} if the working tree contains any uncommitted changes —
     * staged or unstaged, tracked or untracked.
     *
     * <p>Uses {@code git status --porcelain} — empty output means clean tree.
     * This is more thorough than {@link #hasChanges(Vault)}, which only checks
     * unstaged modifications to tracked files. Pure query — emits no log, per
     * the project's logging conventions.</p>
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
            throw new GitException("unexpected network error on local status operation", e);
        }
    }

    /**
     * Returns the human-readable output of {@code git status} for the given vault.
     *
     * <p>Intended for interactive CLI use ({@code NomadSync status}) — the output
     * is meant to be printed directly to {@code stdout} for the user to read, not
     * logged. Unlike {@link #hasUncommittedChanges(Vault)}, which uses
     * {@code --porcelain} to produce machine-readable output for boolean checks,
     * this method returns the full human-readable format. Pure query — emits no
     * log itself, per the project's logging conventions; the caller decides
     * whether/how to present the returned text.</p>
     *
     * @param vault the vault to inspect
     * @return trimmed output of {@code git status}, never {@code null}
     * @throws GitException         if a Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public String status(Vault vault) throws GitException, InterruptedException {
        try {
            return CommandUtil.runCommandWithLines(vault.getPath(),
                    List.of(gitExecutable, "status"));
        } catch (NetworkException e) {
            throw new GitException("unexpected network error on local status operation", e);
        }
    }

    /**
     * Returns a short summary of vault changes using {@code git status --porcelain}.
     *
     * <p>Intended for CLI vault show/list commands. Returns one line per modified
     * file with its status prefix (M = modified, A = added, D = deleted, ?? = untracked).</p>
     *
     * <p>Output is truncated to {@code maxLines} lines. If the actual output exceeds
     * the limit, a {@code "..."} sentinel is appended after the last included line.
     * Special values:</p>
     * <ul>
     *   <li>{@code -1} — full output, no truncation</li>
     *   <li>{@code 0} — returns an empty string regardless of vault state</li>
     *   <li>{@code N > 0} — first N lines, followed by {@code "..."} if more exist</li>
     * </ul>
     *
     * <p>Pure query — emits no log, per the project's logging conventions.</p>
     *
     * @param vault    the vault to inspect
     * @param maxLines maximum number of status lines to return; {@code -1} for full output
     * @return trimmed output of {@code git status --porcelain}, empty string if clean
     * @throws GitException         if a Git error occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public String statusShort(Vault vault, int maxLines) throws GitException, InterruptedException {
        try {
            String status = CommandUtil.runCommandWithLines(vault.getPath(),
                    List.of(gitExecutable, "status", "--porcelain"));
            if (status.isEmpty()) return "";  // clean repo — avoid the "".split() single-empty-token trap
            String[] tokens = status.split("\n");
            StringBuilder result = new StringBuilder();
            AtomicInteger index = new AtomicInteger();
            Arrays.asList(tokens).forEach((token) -> {
                if (index.get() < maxLines) {
                    result.append(token).append("\n");
                } else if (index.get() == maxLines) {
                    result.append("...\n");
                }
                index.getAndIncrement();
            });
            return result.toString();
        } catch (NetworkException e) {
            throw new GitException("unexpected network error on local status operation", e);
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
     *       <li>FIFO snapshot via {@link VaultService#makeVaultSnapshot(Vault)}.</li>
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
     * as the prefix — e.g. {@code Alice_public-vault_2026-06-16_10-30}.
     * This avoids collisions between two vaults with the same {@code name} but
     * different {@code owner}s sharing the same {@code backupsRoot} or
     * {@code conflictsRoot}.</p>
     *
     * <h2>Exception wrapping</h2>
     * <p>{@link GitignoreException} from the snapshot step is wrapped as
     * {@link GitException} — the orchestrator handles a single exception hierarchy
     * and applies its retry + backoff strategy uniformly.</p>
     *
     * <p>Logging: a single {@code INFO} line announces the operation at the start;
     * exit codes and raw command output for each step are logged at {@code DEBUG} —
     * this method predates the intro/outro standardisation but already matched it,
     * so its internal logging is unchanged.</p>
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
        logService.info("synchronize - " + vault.getRepoSlug() + " - synchronizing vault");
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
                // Abort any in-progress merge — non-zero exit is normal if no merge was running.
                CommandUtil.runCommand(
                        vaultPath, List.of(gitExecutable, "merge", "--abort"), logService);

                try {
                    vaultService.makeVaultSnapshot(vault);
                } catch (GitignoreException e) {
                    throw new GitException("Snapshot failed - gitignore read error", e);
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
                                List.of(gitExecutable, "--no-pager", "show",
                                        "FETCH_HEAD:" + file),
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