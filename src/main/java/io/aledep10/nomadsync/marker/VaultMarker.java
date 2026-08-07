package io.aledep10.nomadsync.marker;

import io.aledep10.nomadsync.util.DateFormats;

import java.util.UUID;

/**
 * Marker confirming that a given filesystem directory is claimed by a specific
 * registered vault, identified by its stable {@code id} (not {@code repoSlug},
 * which can change via {@code vault update}/{@code relocate}).
 */
public final class VaultMarker extends Marker {

    private final String repoSlug;
    private final String workspacePath;

    private VaultMarker(String id, String repoSlug, String workspacePath,
                         String createdAt, String lastUpdate) {
        super(id, MarkerType.VAULT, createdAt, lastUpdate);
        this.repoSlug = repoSlug;
        this.workspacePath = workspacePath;
    }

    /**
     * Creates a brand-new marker with an explicit id and timestamp —
     * {@code createdAt} and {@code lastUpdate} start equal.
     *
     * <p>Preferred form for tests: fully deterministic, no dependency on
     * {@link UUID#randomUUID()} or the system clock.</p>
     */
    public static VaultMarker create(String id, String repoSlug, String workspacePath, String now) {
        return new VaultMarker(id, repoSlug, workspacePath, now, now);
    }

    /**
     * Creates a brand-new marker with a freshly generated {@code id} and the
     * current timestamp — convenience for production call sites that don't
     * need control over either. Not deterministic: never use in tests.
     */
    public static VaultMarker create(String repoSlug, String workspacePath) {
        return create(UUID.randomUUID().toString(), repoSlug, workspacePath, DateFormats.nowLog());
    }

    /**
     * Creates a brand-new marker with a freshly generated {@code id} but an
     * explicit timestamp — for tests that want a deterministic timestamp
     * without caring about the specific {@code id} value.
     */
    static VaultMarker create(String repoSlug, String workspacePath, String now) {
        return create(UUID.randomUUID().toString(), repoSlug, workspacePath, now);
    }

    public String repoSlug() { return repoSlug; }
    public String workspacePath() { return workspacePath; }

    @Override
    public String localName() {
        return repoSlug;
    }

    /**
     * Returns a copy with {@code lastUpdate} refreshed — {@code createdAt} is preserved.
     */
    @Override
    public VaultMarker withRefreshedTimestamp(String now) {
        return new VaultMarker(id(), repoSlug, workspacePath, createdAt(), now);
    }

    /**
     * @see Marker#withCreatedAt(String)
     */
    @Override
    public VaultMarker withCreatedAt(String createdAt) {
        return new VaultMarker(id(), repoSlug, workspacePath, createdAt, lastUpdate());
    }

    /**
     * Returns a copy with {@code workspacePath} replaced and {@code lastUpdate}
     * refreshed — {@code id}, {@code repoSlug}, and {@code createdAt} are
     * preserved. Used when a vault's containing workspace is relocated: the
     * marker's identity transfers to the new location, but it is not a new
     * marker — its {@code createdAt} must survive the transfer.
     *
     * @param newWorkspacePath the workspace's new root
     * @param now              the current timestamp for {@code lastUpdate}
     */
    public VaultMarker withWorkspacePath(String newWorkspacePath, String now) {
        return new VaultMarker(id(), repoSlug, newWorkspacePath, createdAt(), now);
    }

    @Override
    protected String typeSpecificFieldsForDebug() {
        return "repoSlug=" + repoSlug + ", workspacePath=" + workspacePath;
    }
}
