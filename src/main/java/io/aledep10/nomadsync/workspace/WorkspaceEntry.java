package io.aledep10.nomadsync.workspace;

import java.util.Objects;

/**
 * Domain representation of a single registered workspace entry.
 *
 * <p>Mirrors {@code io.aledep10.nomadsync.vault.Vault} in spirit: a plain,
 * mutable data holder with no persistence or validation logic of its own —
 * uniqueness of {@link #workspaceName} and the "exactly one default" invariant
 * are enforced by the owning service (e.g. {@code WorkspaceService}), not here
 * (see {@code NomadSync-WSP-001}).</p>
 *
 * <h2>{@code isDefault}</h2>
 * <p>Primitive {@code boolean} here, unlike the boxed {@link Boolean} used in
 * {@link io.aledep10.nomadsync.dto.WorkspaceEntryDto} — the domain layer only
 * ever needs the resolved true/false state, never the "absent from JSON"
 * distinction that motivates the DTO's boxed type. The DTO boundary
 * ({@code toDomain()}/{@code fromDomain()}) is where the two representations
 * are bridged.</p>
 *
 * <p>No Jackson annotations — see {@code NomadSync-VLT-004}: annotations live
 * exclusively in the {@code dto} package, with
 * {@link io.aledep10.nomadsync.dto.WorkspaceEntryDto} as the sole
 * (de)serialization counterpart.</p>
 */
public class WorkspaceEntry {

    private String workspaceName;
    private String path;
    private boolean isDefault;

    /**
     * Creates a new workspace entry.
     *
     * @param workspaceName unique identifier within the registry — distinct
     *                      from {@code vault.name} by design (see
     *                      {@code NomadSync-WSP-003})
     * @param path          absolute, normalized filesystem path to the workspace
     *                      root (the directory containing {@code .nomadsync-workspace/})
     * @param isDefault     whether this entry is the current default workspace —
     *                      the caller is responsible for the "exactly one default"
     *                      invariant across the registry, not this constructor
     */
    public WorkspaceEntry(String workspaceName, String path, boolean isDefault) {
        this.workspaceName = workspaceName;
        this.path = path;
        this.isDefault = isDefault;
    }

    /**
     * Returns a new, independent instance with the same field values.
     *
     * <p>Used by {@link io.aledep10.nomadsync.service.WorkspaceService}'s query
     * methods ({@code findAll}/{@code findByName}/{@code findDefault}) so the live
     * instance held in its internal cache is never handed out — mutating the
     * returned copy has no effect on the source of truth (see
     * {@code NomadSync-WSP-005}).</p>
     *
     * @return a new {@link WorkspaceEntry} with the same {@code workspaceName},
     *         {@code path}, and {@code isDefault} as this one
     */
    public WorkspaceEntry copy() {
        return new WorkspaceEntry(workspaceName, path, isDefault);
    }

    public String getWorkspaceName() {
        return workspaceName;
    }

    public void setWorkspaceName(String workspaceName) {
        this.workspaceName = workspaceName;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    /**
     * Equality and identity are based solely on {@link #workspaceName} —
     * the field whose uniqueness is the registry's core invariant
     * ({@code NomadSync-WSP-001}), independent of {@link #path} or
     * {@link #isDefault}, both of which are expected to change over the
     * entry's lifetime ({@code workspace rename}/{@code relocate}/{@code use}).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkspaceEntry other)) return false;
        return Objects.equals(workspaceName, other.workspaceName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workspaceName);
    }

    @Override
    public String toString() {
        return "WorkspaceEntry{workspaceName='" + workspaceName + "', path='" + path
                + "', isDefault=" + isDefault + "}";
    }
}
