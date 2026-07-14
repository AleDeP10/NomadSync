package io.aledep10.nomadsync.vault;

import java.nio.file.Path;

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
 * as {@code <owner>/<name>} (e.g. {@code AleDeP10/public-vault}). It is the
 * canonical unique identifier across devices and is used as the
 * {@code universalId} field in structured log entries, as the uniqueness
 * constraint enforced by {@link io.aledep10.nomadsync.service.VaultService},
 * and as the CLI {@code --vault} resolution key.</p>
 *
 * <h2>Mutability</h2>
 * <p>{@link #id} is immutable after construction. All other fields are mutable
 * to support rename, relocation, and credential rotation via
 * {@link io.aledep10.nomadsync.service.VaultService#update(Vault)} or the
 * {@code NomadSync config} CLI operation.</p>
 *
 * <h2>Git configuration</h2>
 * <p>The optional fields {@link #gitBranch} and {@link #gitRemote} allow
 * per-vault overrides of the global {@code git.branch} and {@code git.remote}
 * settings in {@code config.properties}. Useful for legacy repositories that
 * use {@code master} instead of {@code main}, or for remotes named other than
 * {@code origin}.</p>
 *
 * <h2>Git credentials</h2>
 * <p>{@link #gitUsername} is the GitHub username used for authentication — it
 * may differ from {@link #owner}. Example: Alessandro ({@code AleDeP10})
 * contributes to a vault owned by Gabriela ({@code belmani-apex}); his
 * {@code gitUsername} is {@code AleDeP10} while {@code owner} is
 * {@code belmani-apex}.</p>
 *
 * <p>All credential fields are optional — if absent, the global Git
 * configuration and credential store are used. Credential resolution order:
 * per-vault field → global {@code config.properties} → {@code ~/.gitconfig}.</p>
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
    private String gitBranch;
    private String gitRemote;

    /**
     * Minimal constructor — used when Git credentials and configuration are
     * managed globally via {@code config.properties} or {@code ~/.gitconfig}.
     *
     * @param id    unique vault identifier — immutable after construction
     * @param owner GitHub account that owns the remote repository
     * @param name  human-readable vault name, also the remote repository name
     * @param path  absolute path to the vault directory on the local filesystem
     */
    public Vault(String id, String owner, String name, String path) {
        this(id, owner, name, path, null, null, null, null, null, null);
    }

    /**
     * Full constructor — used by {@link io.aledep10.nomadsync.dto.VaultDto}
     * after Jackson deserialisation.
     *
     * @param id          unique vault identifier — immutable after construction
     * @param owner       GitHub account that owns the remote repository
     * @param name        human-readable vault name, also the remote repository name
     * @param path        absolute path to the vault directory on the local filesystem
     * @param gitName     Git {@code user.name} override for commits, or {@code null}
     *                    to inherit from global config
     * @param gitEmail    Git {@code user.email} override for commits, or {@code null}
     *                    to inherit from global config
     * @param gitUsername GitHub username for authentication, or {@code null} to
     *                    inherit from global config. May differ from {@code owner}
     *                    when contributing to another account's vault.
     * @param gitToken    GitHub personal access token for push/pull, or {@code null}
     *                    to use the system credential store
     * @param gitBranch   Git branch override (e.g. {@code "master"} for legacy repos),
     *                    or {@code null} to use the global {@code git.branch} setting
     * @param gitRemote   Git remote override (e.g. {@code "upstream"}), or {@code null}
     *                    to use the global {@code git.remote} setting
     */
    public Vault(String id, String owner, String name, String path,
                 String gitName, String gitEmail, String gitUsername, String gitToken,
                 String gitBranch, String gitRemote) {
        this.id          = id;
        this.owner       = owner;
        this.name        = name;
        this.path        = Path.of(path).toString();
        this.gitName     = gitName;
        this.gitEmail    = gitEmail;
        this.gitUsername = gitUsername;
        this.gitToken    = gitToken;
        this.gitBranch   = gitBranch;
        this.gitRemote   = gitRemote;
    }

    // ── Identity ──────────────────────────────────────────────────────────────

    /**
     * Returns the unique vault identifier.
     *
     * <p>Immutable after construction — used as the primary key in
     * {@link io.aledep10.nomadsync.service.VaultService}'s internal map.</p>
     */
    public String getId() { return id; }

    /**
     * Returns the canonical vault identifier: {@code <owner>/<name>},
     * e.g. {@code AleDeP10/public-vault}.
     *
     * <p>Derived from {@link #owner} and {@link #name} — not persisted separately
     * in {@code vaults.json}. Used as:</p>
     * <ul>
     *   <li>the uniqueness constraint enforced by
     *       {@link io.aledep10.nomadsync.service.VaultService}</li>
     *   <li>the {@code universalId} in structured log entries
     *       ({@link io.aledep10.nomadsync.service.LogService#withVault(String)})</li>
     *   <li>the snapshot/conflict directory prefix in
     *       {@link io.aledep10.nomadsync.service.GitService}</li>
     *   <li>the {@code --vault} resolution key in the CLI (GRM M7)</li>
     * </ul>
     */
    public String getRepoSlug() { return owner + "/" + name; }

    // ── Core configuration (mutable) ──────────────────────────────────────────

    /**
     * Returns the GitHub account that owns the remote repository.
     *
     * <p>Together with {@link #name}, forms the {@link #getRepoSlug()}.</p>
     */
    public String getOwner() { return owner; }

    /**
     * Updates the owner.
     *
     * <p>Changes {@link #getRepoSlug()} — ensure the new slug does not collide
     * with an existing vault before calling
     * {@link io.aledep10.nomadsync.service.VaultService#update(Vault)}.</p>
     */
    public void setOwner(String owner) { this.owner = owner; }

    /**
     * Returns the human-readable vault name, also the remote repository name.
     *
     * <p>Together with {@link #owner}, forms the {@link #getRepoSlug()}.</p>
     */
    public String getName() { return name; }

    /**
     * Updates the vault name.
     *
     * <p>Changes {@link #getRepoSlug()} — ensure the new slug does not collide
     * with an existing vault before calling
     * {@link io.aledep10.nomadsync.service.VaultService#update(Vault)}.</p>
     */
    public void setName(String name) { this.name = name; }

    /** Returns the absolute path to the vault directory on the local filesystem. */
    public String getPath() { return path; }

    /**
     * Updates the vault path.
     *
     * <p>Ensure the new path does not collide with an existing vault's path
     * before calling
     * {@link io.aledep10.nomadsync.service.VaultService#update(Vault)}.</p>
     */
    public void setPath(String path) { this.path = Path.of(path).toString(); }

    // ── Git configuration (optional, mutable) ─────────────────────────────────

    /**
     * Returns the Git branch for this vault, or {@code null} to use the
     * global {@code git.branch} setting from {@code config.properties}.
     *
     * <p>Set to {@code "master"} for legacy repositories that have not
     * been migrated to {@code main}.</p>
     */
    public String getGitBranch() { return gitBranch; }

    /** Updates the Git branch override for this vault. */
    public void setGitBranch(String gitBranch) { this.gitBranch = gitBranch; }

    /**
     * Returns the Git remote for this vault, or {@code null} to use the
     * global {@code git.remote} setting from {@code config.properties}.
     *
     * <p>Set to a value other than {@code "origin"} for repositories where
     * the synchronisation remote is not the default one.</p>
     */
    public String getGitRemote() { return gitRemote; }

    /** Updates the Git remote override for this vault. */
    public void setGitRemote(String gitRemote) { this.gitRemote = gitRemote; }

    // ── Git credentials (optional, mutable) ───────────────────────────────────

    /**
     * Returns the Git {@code user.name} for commits on this vault, or
     * {@code null} to use the global {@code git.name} setting.
     */
    public String getGitName() { return gitName; }

    /** Updates the Git {@code user.name} override for this vault. */
    public void setGitName(String gitName) { this.gitName = gitName; }

    /**
     * Returns the Git {@code user.email} for commits on this vault, or
     * {@code null} to use the global {@code git.email} setting.
     */
    public String getGitEmail() { return gitEmail; }

    /** Updates the Git {@code user.email} override for this vault. */
    public void setGitEmail(String gitEmail) { this.gitEmail = gitEmail; }

    /**
     * Returns the GitHub username for authentication on this vault, or
     * {@code null} to use the global {@code git.username} setting.
     *
     * <p>May differ from {@link #getOwner()} when a contributor accesses
     * a vault owned by another account.</p>
     */
    public String getGitUsername() { return gitUsername; }

    /** Updates the GitHub username override for this vault. */
    public void setGitUsername(String gitUsername) { this.gitUsername = gitUsername; }

    /**
     * Returns the GitHub personal access token for push/pull on this vault,
     * or {@code null} to use the global {@code git.token} setting or the
     * system credential store.
     */
    public String getGitToken() { return gitToken; }

    /** Updates the GitHub PAT override for this vault. */
    public void setGitToken(String gitToken) { this.gitToken = gitToken; }

    // ── Object ────────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        return "Vault{id='%s', repoSlug='%s', path='%s'}"
                .formatted(id, getRepoSlug(), path);
    }
}