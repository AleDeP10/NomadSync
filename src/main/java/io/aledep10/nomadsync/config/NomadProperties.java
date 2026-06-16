package io.aledep10.nomadsync.config;

/**
 * Centralised registry of all {@code config.properties} keys used by NomadSync.
 *
 * <p>Every key that NomadSync reads from {@code config.properties} is declared here
 * as a string constant. This eliminates magic strings scattered across the codebase
 * and provides a single source of truth for configuration key names.</p>
 *
 * <p>Constants are grouped in nested static classes by functional domain, mirroring
 * the key namespace in {@code config.properties}:</p>
 * <pre>
 *   git.*          → {@link Git}
 *   path.*         → {@link Path}
 *   log.*          → {@link Log}
 *   autosave.*     → {@link Autosave}
 *   commit.*       → {@link Commit}
 *   socket.*       → {@link Socket}
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * properties.getProperty(NomadProperties.Git.EXECUTABLE, "git");
 * properties.getProperty(NomadProperties.Autosave.INTERVAL_MINUTES, "15");
 * }</pre>
 *
 * <h2>config.properties reference</h2>
 * <pre>
 * # ── Git ──────────────────────────────────────────────────────────────────────
 * git.executable=git
 * git.remote=origin
 * git.branch=main
 * git.name=Your Name
 * git.email=you@example.com
 * git.username=your-github-username
 * git.token=ghp_...
 *
 * # ── Paths ────────────────────────────────────────────────────────────────────
 * path.vaults=./vaults.json
 * path.backup=./backup
 * path.conflicts=./remote_conflicts
 *
 * # ── Logging ──────────────────────────────────────────────────────────────────
 * log.writers=console,file,seq
 * log.path=logs/nomadsync.log
 * log.level=INFO
 * log.seq.url=http://localhost:5341
 *
 * # ── Autosave ─────────────────────────────────────────────────────────────────
 * autosave.interval.minutes=15
 *
 * # ── Commit ───────────────────────────────────────────────────────────────────
 * commit.editor=notepad++
 *
 * # ── Socket ───────────────────────────────────────────────────────────────────
 * socket.host=localhost
 * socket.port=4242
 * socket.retryDelay=30000
 * </pre>
 *
 * <p>Non-instantiable — all members are {@code static}.</p>
 */
public final class NomadProperties {

    private NomadProperties() {}


    // ─────────────────────────────────────────────
    // git.*
    // ─────────────────────────────────────────────

    /**
     * Keys governing Git CLI configuration and global credentials.
     *
     * <p>All values can be overridden per-vault via the corresponding fields in
     * {@code vaults.json} ({@code gitName}, {@code gitEmail}, etc.). Resolution
     * order: per-vault field → this global property → {@code ~/.gitconfig}.</p>
     */
    public static final class Git {

        private Git() {}

        /**
         * Path to the Git executable, or just {@code "git"} if Git is on {@code PATH}.
         * <br>Default: {@code "git"}.
         */
        public static final String EXECUTABLE = "git.executable";

        /**
         * Git remote name used for push/pull.
         * <br>Default: {@code "origin"}.
         * <br>Per-vault override: {@code Vault.gitRemote}.
         */
        public static final String REMOTE = "git.remote";

        /**
         * Git branch used for push.
         * <br>Default: {@code "main"}.
         * <br>Per-vault override: {@code Vault.gitBranch} — set to {@code "master"}
         * for legacy repositories.
         */
        public static final String BRANCH = "git.branch";

        /**
         * Global Git {@code user.name} written to vault's local {@code .git/config}
         * by {@link io.aledep10.nomadsync.service.GitService#bootstrapVault}.
         * <br>Per-vault override: {@code Vault.gitName}.
         */
        public static final String NAME = "git.name";

        /**
         * Global Git {@code user.email} written to vault's local {@code .git/config}
         * by {@link io.aledep10.nomadsync.service.GitService#bootstrapVault}.
         * <br>Per-vault override: {@code Vault.gitEmail}.
         */
        public static final String EMAIL = "git.email";

        /**
         * Global GitHub username used to build the authenticated remote URL.
         * <br>Per-vault override: {@code Vault.gitUsername} — used when a contributor
         * accesses a vault owned by a different account.
         */
        public static final String USERNAME = "git.username";

        /**
         * Global GitHub personal access token embedded in the remote URL by
         * {@link io.aledep10.nomadsync.service.GitService#bootstrapVault}.
         * <br>Per-vault override: {@code Vault.gitToken}.
         * <br><strong>Security note</strong>: this value is written to the vault's
         * local {@code .git/config} — keep {@code config.properties} out of version
         * control ({@code .gitignore}).
         */
        public static final String TOKEN = "git.token";
    }


    // ─────────────────────────────────────────────
    // path.*
    // ─────────────────────────────────────────────

    /**
     * Keys governing filesystem paths used by NomadSync at runtime.
     */
    public static final class Path {

        private Path() {}

        /**
         * Path to the {@code vaults.json} file listing all registered vaults.
         * <br>Default: {@code "./vaults.json"}.
         */
        public static final String VAULTS = "path.vaults";

        /**
         * Root directory for FIFO vault snapshots created before conflict resolution.
         * <br>Default: {@code "./backups"} (relative to working directory).
         * <br>Each vault snapshot is named {@code <owner>_<name>_<timestamp>/}.
         */
        public static final String BACKUP = "path.backup";

        /**
         * Root directory for remote conflict files saved during {@code SYNCHRONIZE}.
         * <br>Default: {@code "./remote-conflicts"} (relative to working directory).
         * <br>Each conflict session is named {@code <owner>_<name>_<timestamp>/}.
         */
        public static final String CONFLICTS = "path.conflicts";
    }


    // ─────────────────────────────────────────────
    // log.*
    // ─────────────────────────────────────────────

    /**
     * Keys governing the logging subsystem.
     *
     * @see io.aledep10.nomadsync.service.LogService
     */
    public static final class Log {

        private Log() {}

        /**
         * Comma-separated list of active log writers.
         * <br>Accepted tokens: {@code console}, {@code file}, {@code seq}.
         * <br>Default: {@code "console,file"}.
         * <br>Unknown tokens are skipped with a {@code stderr} warning at startup.
         */
        public static final String WRITERS = "log.writers";

        /**
         * Absolute or relative path to the log file.
         * <br>Required when {@code log.writers} includes {@code file}.
         * <br>The parent directory is created automatically if absent.
         */
        public static final String PATH = "log.path";

        /**
         * Minimum log level. Events below this level are discarded.
         * <br>Accepted values: {@code DEBUG}, {@code INFO}, {@code WARN}, {@code ERROR}.
         * <br>Default: {@code "INFO"}.
         */
        public static final String LEVEL = "log.level";

        /**
         * Base URL of the Seq structured log server.
         * <br>Accepted formats: {@code http://host:port} (base URL) or
         * {@code http://host:port/api/events/raw} (full ingestion URL).
         * <br>Required when {@code log.writers} includes {@code seq}.
         */
        public static final String SEQ_URL = "log.seq.url";

        /**
         * Seq API key for authenticated ingestion.
         * <br>Optional — defaults to {@code ""} (authentication disabled).
         */
        public static final String SEQ_API_KEY = "log.seq.apiKey";
    }


    // ─────────────────────────────────────────────
    // autosave.*
    // ─────────────────────────────────────────────

    /**
     * Keys governing the periodic autosave scheduler.
     *
     * @see io.aledep10.nomadsync.scheduler.AutosaveScheduler
     */
    public static final class Autosave {

        private Autosave() {}

        /**
         * Interval between autosave cycles, in minutes.
         * <br>Default: {@code "15"}.
         * <br>The first autosave is delayed by one full interval after startup —
         * the {@code PULL_LOGON} event already handles the initial state.
         */
        public static final String INTERVAL_MINUTES = "autosave.interval.minutes";
    }


    // ─────────────────────────────────────────────
    // commit.*
    // ─────────────────────────────────────────────

    /**
     * Keys governing the interactive commit operation ({@code NomadSync commit}).
     */
    public static final class Commit {

        private Commit() {}

        /**
         * Path or command name of the text editor to open for commit messages.
         * <br>Resolution order: {@code --editor} CLI flag →
         * this property → {@code EDITOR} environment variable →
         * OS default ({@code notepad} on Windows, {@code nano} on Unix).
         * <br>Example: {@code commit.editor=notepad++}
         */
        public static final String EDITOR = "commit.editor";
    }


    // ─────────────────────────────────────────────
    // socket.*
    // ─────────────────────────────────────────────

    /**
     * Keys governing the local socket used for IPC between the tray process
     * and CLI commands.
     *
     * @see io.aledep10.nomadsync.tray.SocketServer
     * @see io.aledep10.nomadsync.tray.SocketClient
     */
    public static final class Socket {

        private Socket() {}

        /**
         * Hostname the socket server listens on and the client connects to.
         * <br>Default: {@code "localhost"}.
         */
        public static final String HOST = "socket.host";

        /**
         * Port the socket server listens on.
         * <br>Default: {@code "4242"}.
         */
        public static final String PORT = "socket.port";

        /**
         * Delay in milliseconds between client connection retries.
         * <br>Default: {@code "30000"} (30 seconds).
         */
        public static final String RETRY_DELAY = "socket.retryDelay";
    }
}