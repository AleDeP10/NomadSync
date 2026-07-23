package io.aledep10.nomadsync.marker;

import io.aledep10.nomadsync.util.DateFormats;

import java.util.UUID;

/**
 * Marker confirming that a given filesystem directory is claimed as a
 * workspace's home — the folder containing an adjacent
 * {@code config.properties}/{@code catalog.json} pair.
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

    /**
     * Creates a brand-new marker with a freshly generated {@code id} and the
     * current timestamp — convenience for production call sites that don't
     * need control over either. Not deterministic: never use in tests.
     */
    public static WorkspaceMarker create(String workspaceName) {
        return create(UUID.randomUUID().toString(), workspaceName, DateFormats.nowLog());
    }

    /**
     * Creates a brand-new marker with a freshly generated {@code id} but an
     * explicit timestamp — for tests that want a deterministic timestamp
     * without caring about the specific {@code id} value.
     */
    static WorkspaceMarker create(String workspaceName, String now) {
        return create(UUID.randomUUID().toString(), workspaceName, now);
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
}
