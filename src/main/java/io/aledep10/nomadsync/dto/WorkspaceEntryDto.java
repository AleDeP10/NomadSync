package io.aledep10.nomadsync.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aledep10.nomadsync.workspace.WorkspaceEntry;

/**
 * Jackson DTO for serialising and deserialising a single workspace entry
 * in {@code workspaces.json}.
 *
 * <p>Keeps all Jackson annotations out of the {@link WorkspaceEntry} domain
 * class — same split enforced for {@code Vault}/{@link VaultDto}
 * (see {@code NomadSync-VLT-004}). Use {@link #toDomain()} after
 * deserialisation, and {@link #fromDomain(WorkspaceEntry)} before
 * serialisation.</p>
 *
 * <h2>{@code isDefault} — boxed {@link Boolean}, not primitive</h2>
 * <p>The first DTO in this codebase to model a genuinely optional property
 * (as opposed to {@code Vault}'s Git override fields, which are optional but
 * always {@code String}). The "absent means false" contract is enforced by
 * {@link JsonInclude @JsonInclude(NON_NULL)} on the field — this value is
 * {@link Boolean#TRUE} for the one default entry, {@code null} (and thus
 * omitted from the serialised JSON) for every other entry; an explicit
 * {@code false} is never written. A primitive {@code boolean} cannot
 * represent that third state.</p>
 */
public class WorkspaceEntryDto {

    private final String workspaceName;
    private final String path;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final Boolean isDefault;

    /**
     * Jackson deserialisation constructor.
     *
     * @param workspaceName unique identifier within the registry
     * @param path          absolute, normalized filesystem path to the workspace root
     * @param isDefault     {@code true} if this is the current default workspace,
     *                      or {@code null}/absent (equivalent to "not default")
     */
    @JsonCreator
    public WorkspaceEntryDto(
            @JsonProperty("workspaceName") String workspaceName,
            @JsonProperty("path")          String path,
            @JsonProperty("isDefault")     Boolean isDefault) {
        this.workspaceName = workspaceName;
        this.path          = path;
        this.isDefault     = isDefault;
    }

    /**
     * Converts this DTO to the {@link WorkspaceEntry} domain object.
     *
     * @return a fully populated {@link WorkspaceEntry} instance — {@code null}
     *         on the boxed field collapses to the domain's primitive {@code false}
     */
    public WorkspaceEntry toDomain() {
        return new WorkspaceEntry(workspaceName, path, Boolean.TRUE.equals(isDefault));
    }

    /**
     * Creates a {@link WorkspaceEntryDto} from a {@link WorkspaceEntry} domain
     * object for serialisation.
     *
     * @param entry the domain object to convert
     * @return a {@link WorkspaceEntryDto} ready for Jackson serialisation —
     *         {@code isDefault} is {@link Boolean#TRUE} when set, {@code null}
     *         otherwise (never an explicit {@code false})
     */
    public static WorkspaceEntryDto fromDomain(WorkspaceEntry entry) {
        return new WorkspaceEntryDto(
                entry.getWorkspaceName(),
                entry.getPath(),
                entry.isDefault() ? Boolean.TRUE : null);
    }

    // ── Getters — required by Jackson for serialisation ───────────────────────

    public String getWorkspaceName() { return workspaceName; }
    public String getPath()          { return path;          }
    public Boolean getIsDefault()    { return isDefault;     }
}
