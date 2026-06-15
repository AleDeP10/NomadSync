package io.aledep10.nomadsync.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aledep10.nomadsync.orchestrator.Vault;

/**
 * Jackson DTO for serialising and deserialising a single vault entry
 * in {@code vaults.json}.
 *
 * <p>Keeps all Jackson annotations out of the {@link Vault} domain class.
 * Use {@link #toDomain()} after deserialisation to obtain the domain object,
 * and {@link #fromDomain(Vault)} before serialisation to obtain the DTO.</p>
 *
 * <h2>Mapping</h2>
 * <pre>
 * JSON ──→ VaultDto ──→ toDomain() ──→ Vault
 * Vault ──→ fromDomain() ──→ VaultDto ──→ JSON
 * </pre>
 */
public class VaultDto {

    private final String id;
    private final String owner;
    private final String name;
    private final String path;
    private final String gitName;
    private final String gitEmail;
    private final String gitUsername;
    private final String gitToken;

    /**
     * Jackson deserialisation constructor.
     * All credential fields are optional — Jackson sets them to {@code null} if absent.
     */
    @JsonCreator
    public VaultDto(
            @JsonProperty("id")          String id,
            @JsonProperty("owner")       String owner,
            @JsonProperty("name")        String name,
            @JsonProperty("path")        String path,
            @JsonProperty("gitName")     String gitName,
            @JsonProperty("gitEmail")    String gitEmail,
            @JsonProperty("gitUsername") String gitUsername,
            @JsonProperty("gitToken")    String gitToken) {
        this.id          = id;
        this.owner       = owner;
        this.name        = name;
        this.path        = path;
        this.gitName     = gitName;
        this.gitEmail    = gitEmail;
        this.gitUsername = gitUsername;
        this.gitToken    = gitToken;
    }

    /**
     * Converts this DTO to the {@link Vault} domain object.
     *
     * @return a fully populated {@link Vault} instance
     */
    public Vault toDomain() {
        return new Vault(id, owner, name, path, gitName, gitEmail, gitUsername, gitToken);
    }

    /**
     * Creates a {@link VaultDto} from a {@link Vault} domain object for serialisation.
     *
     * @param vault the domain object to convert
     * @return a {@link VaultDto} ready for Jackson serialisation
     */
    public static VaultDto fromDomain(Vault vault) {
        return new VaultDto(
                vault.getId(),
                vault.getOwner(),
                vault.getName(),
                vault.getPath(),
                vault.getGitName(),
                vault.getGitEmail(),
                vault.getGitUsername(),
                vault.getGitToken());
    }

    // ── Getters — required by Jackson for serialisation ───────────────────────

    public String getId()          { return id; }
    public String getOwner()       { return owner; }
    public String getName()        { return name; }
    public String getPath()        { return path; }
    public String getGitName()     { return gitName; }
    public String getGitEmail()    { return gitEmail; }
    public String getGitUsername() { return gitUsername; }
    public String getGitToken()    { return gitToken; }
}