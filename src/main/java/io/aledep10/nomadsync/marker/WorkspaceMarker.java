package io.aledep10.nomadsync.marker;

import java.util.UUID;

/**
 * Marker confirming that a given filesystem directory is claimed as a
 * workspace's home — the folder containing an adjacent
 * {@code config.properties}/{@code catalog.json} pair.
 *
 * <p>Identified by a stable {@code id}, not {@code workspaceName} — the name
 * can change via {@code workspace update}, same reasoning already applied to
 * {@link VaultMarker} keying on {@code id} rather than {@code repoSlug} (which
 * changes via {@code vault update}/{@code relocate}). {@code id} is derived
 * deterministically from the workspace's path ({@code stableMarkerId}), not a
 * random UUID — the same path claimed again after a process restart must
 * resolve to the same identity, not appear as a different claimant.</p>
 */
public final class WorkspaceMarker extends Marker {

    private final String workspaceName;

    private WorkspaceMarker(String id, String workspaceName, String createdAt, String lastUpdate) {
        super(id, MarkerType.WORKSPACE, createdAt, lastUpdate);
        this.workspaceName = workspaceName;
    }

    /**
     * Creates a brand-new marker with an explicit id and timestamp —
     * {@code createdAt} and {@code lastUpdate} start equal.
     *
     * <p>Preferred form for tests: fully deterministic, no dependency on
     * {@link UUID#randomUUID()} or the system clock.</p>
     */
    public static WorkspaceMarker create(String id, String workspaceName, String now) {
        return new WorkspaceMarker(id, workspaceName, now, now);
    }

    public String workspaceName() { return workspaceName; }

    @Override
    public String localName() {
        return workspaceName;
    }

    /**
     * Returns a copy with {@code lastUpdate} refreshed — {@code createdAt} is preserved.
     */
    @Override
    public WorkspaceMarker withRefreshedTimestamp(String now) {
        return new WorkspaceMarker(id(), workspaceName, createdAt(), now);
    }

    /**
     * @see Marker#withCreatedAt(String)
     */
    @Override
    public WorkspaceMarker withCreatedAt(String createdAt) {
        return new WorkspaceMarker(id(), workspaceName, createdAt, lastUpdate());
    }
}