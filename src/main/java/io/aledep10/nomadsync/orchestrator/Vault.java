package io.aledep10.nomadsync.orchestrator;

/**
 * Represents a registered vault — the static configuration loaded from
 * {@code vaults.json} via {@link io.aledep10.nomadsync.dto.VaultDto}.
 *
 * <h2>Identity</h2>
 * <p>{@link #id} is an opaque UUID generated at registration time and never
 * changes. It is the primary key used internally for O(1) lookup in
 * {@link io.aledep10.nomadsync.service.VaultService}.</p>
 *
 * <p>{@link #getRepoSlug()} is derived from {@link #owner} and {@link #name}
 * as {@code <owner>/<name>} (e.g. {@code AleDeP10/public-vault}). It uniquely
 * identifies the vault across devices and is used as the {@code universalId}
 * field in structured log entries.</p>
 *
 * <h2>Mutability</h2>
 * <p>{@link #id} is immutable after construction. All other fields are mutable
 * to support rename, relocation, and credential rotation.</p>
 *
 * <h2>Git credentials</h2>
 * <p>{@link #gitUsername} is the GitHub username used for
 * {@code git config user.name} — it may differ from {@link #owner}.
 * Example: Alessandro ({@code AleDeP10}) contributes to a Belmani Apex vault
 * owned by Gabriela ({@code belmani-apex}); his {@code gitUsername} is
 * {@code AleDeP10} while {@code owner} is {@code belmani-apex}.</p>
 *
 * <p>All credential fields are optional — if absent, the global Git
 * configuration is used.</p>
 *
 * <h2>Serialisation</h2>
 * <p>JSON deserialisation is handled by {@link io.aledep10.nomadsync.dto.VaultDto}
 * — no Jackson annotations belong in this domain class.</p>
 */
public class Vault {

    private final String id;
    private String owner;
    private String name;
    private String path;

    private String gitName;
    private String gitEmail;
    private String gitUsername;
    private String gitToken;

    /**
     * Minimal constructor — used when Git credentials are managed globally.
     *
     * @param id    unique vault identifier — immutable after construction
     * @param owner GitHub account that owns the remote repository
     * @param name  human-readable vault name, also the remote repository name
     * @param path  absolute path to the vault directory on the local filesystem
     */
    public Vault(String id, String owner, String name, String path) {
        this(id, owner, name, path, null, null, null, null);
    }

    /**
     * Full constructor — used by {@link io.aledep10.nomadsync.dto.VaultDto}
     * after Jackson deserialisation.
     *
     * @param id          unique vault identifier — immutable after construction
     * @param owner       GitHub account that owns the remote repository
     * @param name        human-readable vault name, also the remote repository name
     * @param path        absolute path to the vault directory on the local filesystem
     * @param gitName     Git {@code user.name} for commits, or {@code null}
     * @param gitEmail    Git {@code user.email} for commits, or {@code null}
     * @param gitUsername GitHub username for {@code git config user.name}, or {@code null}.
     *                    May differ from {@code owner} when contributing to another
     *                    account's vault.
     * @param gitToken    GitHub personal access token for push/pull, or {@code null}
     */
    public Vault(String id, String owner, String name, String path,
                 String gitName, String gitEmail, String gitUsername, String gitToken) {
        this.id          = id;
        this.owner       = owner;
        this.name        = name;
        this.path        = path;
        this.gitName     = gitName;
        this.gitEmail    = gitEmail;
        this.gitUsername = gitUsername;
        this.gitToken    = gitToken;
    }

    // ── Identity ──────────────────────────────────────────────────────────────

    /** Returns the unique vault identifier. Immutable after construction. */
    public String getId() { return id; }

    /**
     * Returns the universal vault identifier derived from owner and name:
     * {@code <owner>/<name>}, e.g. {@code AleDeP10/public-vault}.
     *
     * <p>Used as {@code universalId} in structured log entries — allows log
     * queries to be scoped to a specific vault regardless of local path or device.</p>
     */
    public String getRepoSlug() { return owner + "/" + name; }

    // ── Configuration (mutable) ───────────────────────────────────────────────

    /** Returns the GitHub account that owns the remote repository. */
    public String getOwner() { return owner; }

    /** Updates the owner — used when a vault is transferred to another account. */
    public void setOwner(String owner) { this.owner = owner; }

    /** Returns the human-readable vault name, also the remote repository name. */
    public String getName() { return name; }

    /** Updates the vault name — used when the user renames a vault via the tray. */
    public void setName(String name) { this.name = name; }

    /** Returns the absolute path to the vault directory on the local filesystem. */
    public String getPath() { return path; }

    /** Updates the vault path — used when the vault directory is moved. */
    public void setPath(String path) { this.path = path; }

    // ── Git credentials (optional, mutable) ───────────────────────────────────

    /** Returns Git {@code user.name} for commits, or {@code null} for global config. */
    public String getGitName() { return gitName; }
    public void setGitName(String gitName) { this.gitName = gitName; }

    /** Returns Git {@code user.email} for commits, or {@code null} for global config. */
    public String getGitEmail() { return gitEmail; }
    public void setGitEmail(String gitEmail) { this.gitEmail = gitEmail; }

    /**
     * Returns the GitHub username for {@code git config user.name}, or {@code null}.
     *
     * <p>May differ from {@link #getOwner()} — used when a contributor accesses
     * a vault owned by another account.</p>
     */
    public String getGitUsername() { return gitUsername; }
    public void setGitUsername(String gitUsername) { this.gitUsername = gitUsername; }

    /** Returns the GitHub PAT for push/pull, or {@code null} for credential store. */
    public String getGitToken() { return gitToken; }
    public void setGitToken(String gitToken) { this.gitToken = gitToken; }

    @Override
    public String toString() {
        return "Vault{id='%s', repoSlug='%s', path='%s'}"
                .formatted(id, getRepoSlug(), path);
    }
}