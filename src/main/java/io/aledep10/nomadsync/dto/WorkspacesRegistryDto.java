package io.aledep10.nomadsync.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aledep10.nomadsync.util.JsonMapper;

import java.util.List;

/**
 * Jackson DTO for the root wrapper of {@code workspaces.json}.
 *
 * <p>Maps the top-level structure {@code { "workspaces": [...] }} to a list
 * of {@link WorkspaceEntryDto} entries. Used exclusively by {@link JsonMapper}
 * for load and save operations — its constructor is therefore {@code public},
 * not {@code private}: {@code JsonMapper} lives in a different package
 * ({@code io.aledep10.nomadsync.util}) and must be able to call
 * {@code new WorkspacesRegistryDto(...)} directly from
 * {@code saveWorkspacesToFile}.</p>
 *
 * <p>A plain class with an explicit {@link #getWorkspaces()} getter, not a
 * record — {@code JsonMapper} reads it via {@code registry.getWorkspaces()},
 * bean-style, to match {@code VaultDto}'s convention rather than
 * {@code VaultRootDto}'s record-accessor style. The two root DTOs
 * ({@code VaultRootDto} vs. this class) are not required to share the same
 * shape; what matters is that this one matches the calling code that already
 * depends on it.</p>
 *
 * <h2>Root DTO — a recurring micro-pattern, not a shared abstraction</h2>
 * <p>{@link CatalogDto} follows the identical recipe for {@code catalog.json}:
 * a single field, a {@code @JsonCreator} constructor with one
 * {@code @JsonProperty}, one plain getter. The two are deliberately
 * <strong>not</strong> unified under a shared generic base — see
 * {@link CatalogDto}'s Javadoc for the full rationale.</p>
 *
 * <h2>File structure</h2>
 * <pre>{@code
 * {
 *   "workspaces": [
 *     { "workspaceName": "default", "path": "...", "isDefault": true },
 *     { "workspaceName": "laptop-work", "path": "..." }
 *   ]
 * }
 * }</pre>
 */
public class WorkspacesRegistryDto {

    private final List<WorkspaceEntryDto> workspaces;

    /**
     * Jackson deserialisation constructor — also called directly by
     * {@link JsonMapper#saveWorkspacesToFile(java.io.File, List)}
     * when building the DTO for serialisation.
     *
     * @param workspaces the list of workspace DTOs, from the JSON array on
     *                    read or from the in-memory domain list on write
     */
    @JsonCreator
    public WorkspacesRegistryDto(@JsonProperty("workspaces") List<WorkspaceEntryDto> workspaces) {
        this.workspaces = workspaces;
    }

    /**
     * Returns the list of workspace DTOs.
     */
    public List<WorkspaceEntryDto> getWorkspaces() {
        return workspaces;
    }
}
