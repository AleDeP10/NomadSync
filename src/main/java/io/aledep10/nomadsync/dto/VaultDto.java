package io.aledep10.nomadsync.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aledep10.nomadsync.vault.Vault;

/**
 * Jackson DTO for serialising and deserialising a single vault entry
 * in {@code catalog.json}.
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
 *
 * <h2>Optional fields</h2>
 * <p>All Git credential and configuration fields ({@code gitName}, {@code gitEmail},
 * {@code gitUsername}, {@code gitToken}, {@code gitBranch}, {@code gitRemote}) are
 * optional — Jackson sets them to {@code null} if absent from the JSON. This allows
 * {@code catalog.json} entries to carry only the fields that differ from the global
 * configuration in {@code config.properties}.</p>
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
    private final String gitBranch;
    private final String gitRemote;

    /**
     * Jackson deserialisation constructor.
     *
     * @param id          unique vault identifier
     * @param owner       GitHub account that owns the remote repository
     * @param name        human-readable vault name, also the remote repository name
     * @param path        absolute path to the vault directory on the local filesystem
     * @param gitName     Git {@code user.name} override for this vault, or {@code null}
     * @param gitEmail    Git {@code user.email} override for this vault, or {@code null}
     * @param gitUsername GitHub username override for this vault, or {@code null}
     * @param gitToken    GitHub PAT override for this vault, or {@code null}
     * @param gitBranch   Git branch override (e.g. {@code "master"} for legacy repos),
     *                    or {@code null} to use the global {@code git.branch} setting
     * @param gitRemote   Git remote override (e.g. {@code "upstream"} for non-standard
     *                    remotes), or {@code null} to use the global {@code git.remote}
     *                    setting
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
            @JsonProperty("gitToken")    String gitToken,
            @JsonProperty("gitBranch")   String gitBranch,
            @JsonProperty("gitRemote")   String gitRemote) {
        this.id          = id;
        this.owner       = owner;
        this.name        = name;
        this.path        = path;
        this.gitName     = gitName;
        this.gitEmail    = gitEmail;
        this.gitUsername = gitUsername;
        this.gitToken    = gitToken;
        this.gitBranch   = gitBranch;
        this.gitRemote   = gitRemote;
    }

    /**
     * Converts this DTO to the {@link Vault} domain object.
     *
     * @return a fully populated {@link Vault} instance
     */
    public Vault toDomain() {
        return new Vault(id, owner, name, path,
                gitName, gitEmail, gitUsername, gitToken,
                gitBranch, gitRemote);
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
                vault.getGitToken(),
                vault.getGitBranch(),
                vault.getGitRemote());
    }

    // ── Getters — required by Jackson for serialisation ───────────────────────

    public String getId()          { return id;          }
    public String getOwner()       { return owner;       }
    public String getName()        { return name;        }
    public String getPath()        { return path;        }
    public String getGitName()     { return gitName;     }
    public String getGitEmail()    { return gitEmail;    }
    public String getGitUsername() { return gitUsername; }
    public String getGitToken()    { return gitToken;    }
    public String getGitBranch()   { return gitBranch;   }
    public String getGitRemote()   { return gitRemote;   }
}