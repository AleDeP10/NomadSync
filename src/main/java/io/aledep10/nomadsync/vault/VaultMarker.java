package io.aledep10.nomadsync.vault;

/**
 * Domain representation of a {@code .vault} marker — confirms that a given
 * filesystem directory is claimed by a specific registered vault, identified
 * by its stable {@code id} (not {@code repoSlug}, which can change via
 * {@code vault update}/{@code relocate}).
 *
 * <p>Written/refreshed by {@link io.aledep10.nomadsync.service.VaultService#load()}
 * for every already-registered vault ("confirmation" — always succeeds on the
 * vault's own marker), and claimed atomically by {@code create}/{@code add}/
 * {@code relocate} when a directory is first claimed ("claim" — must fail on
 * collision with a different vault's marker).</p>
 *
 * @param id         stable UUID of the owning vault — the authoritative identity check
 * @param repoSlug   {@code <owner>/<name>} at the time of writing, for human-readable diagnostics
 * @param jsonPath   absolute path to the {@code catalog.json} that registers this vault
 * @param createdAt  ISO timestamp of first claim, never changed after creation
 * @param lastUpdate ISO timestamp of the most recent confirmation/claim
 */
public record VaultMarker(String id, String repoSlug, String jsonPath, String createdAt, String lastUpdate) {

    /**
     * Creates a brand-new marker — {@code createdAt} and {@code lastUpdate} start equal.
     */
    public static VaultMarker create(String id, String repoSlug, String jsonPath, String now) {
        return new VaultMarker(id, repoSlug, jsonPath, now, now);
    }

    /**
     * Returns a copy with {@code lastUpdate} refreshed — {@code createdAt} is preserved.
     */
    public VaultMarker withRefreshedTimestamp(String now) {
        return new VaultMarker(id, repoSlug, jsonPath, createdAt, now);
    }
}