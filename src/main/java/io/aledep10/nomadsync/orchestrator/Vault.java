package io.aledep10.nomadsync.orchestrator;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Represents a registered Obsidian vault — the static configuration loaded
 * from {@code vaults.json}.
 *
 * <p>Immutable on {@code id}; {@code name} and {@code path} are mutable to
 * support vault rename and relocation without recreating the object.</p>
 *
 * <p>Deserialised by Jackson via the {@link JsonCreator} constructor —
 * no default constructor or setters required for JSON parsing.</p>
 *
 * <h2>vaults.json structure</h2>
 * <pre>{@code
 * {
 *   "vaults": [
 *     { "id": "A768-6CF3-10B-0000", "name": "obsidian-portfolio",
 *       "path": "C:\\Users\\aless\\obsidian-vaults\\obsidian-portfolio" }
 *   ]
 * }
 * }</pre>
 */
public class Vault {

    private final String id;
    private String name;
    private String path;

    private String gitName;
    private String gitEmail;
    private String gitUsername;
    private String gitToken;

    /**
     * Jackson-compatible constructor. All fields are mapped by name from the JSON.
     *
     * @param id   unique vault identifier — immutable after construction
     * @param name human-readable vault name
     * @param path absolute path to the vault directory on the local filesystem
     */
    public Vault(
            String id,
            String name,
            String path) {
        this(id,name,path,null,null,null,null);
    }

    public Vault(
            String id,
            String name,
            String path,
            String gitName,
            String gitEmail,
            String gitUsername,
            String gitToken) {
        this.id   = id;
        this.name = name;
        this.path = path;
        this.gitName = gitName;
        this.gitEmail = gitEmail;
        this.gitUsername = gitUsername;
        this.gitToken = gitToken;
    }

    /**
     * Returns the unique vault identifier. Immutable after construction.
     *
     * @return vault id
     */
    public String getId() { return id; }

    /**
     * Returns the human-readable vault name.
     *
     * @return vault name
     */
    public String getName() { return name; }

    /**
     * Updates the vault name — used when the user renames a vault via the tray popup.
     *
     * @param name new vault name
     */
    public void setName(String name) { this.name = name; }

    /**
     * Returns the absolute path to the vault directory on the local filesystem.
     *
     * @return vault path
     */
    public String getPath() { return path; }

    /**
     * Updates the vault path — used when the vault directory is moved.
     *
     * @param path new absolute path
     */
    public void setPath(String path) { this.path = path; }

    @Override
    public String toString() {
        return "Vault{id='%s', name='%s', path='%s'}".formatted(id, name, path);
    }

    public String getGitName() {
        return gitName;
    }

    public void setGitName(String gitName) {
        this.gitName = gitName;
    }

    public String getGitEmail() {
        return gitEmail;
    }

    public void setGitEmail(String gitEmail) {
        this.gitEmail = gitEmail;
    }

    public String getGitUsername() {
        return gitUsername;
    }

    public void setGitUsername(String gitUsername) {
        this.gitUsername = gitUsername;
    }

    public String getGitToken() {
        return gitToken;
    }

    public void setGitToken(String gitToken) {
        this.gitToken = gitToken;
    }
}
