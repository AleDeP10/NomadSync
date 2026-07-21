package io.aledep10.nomadsync.marker;

import java.util.Objects;

/**
 * Common ancestor for every reserved-folder marker in the protocol —
 * {@link VaultMarker}, {@link WorkspaceMarker}, and future types.
 *
 * <p>Identity ({@code equals}/{@code hashCode}) is based on {@code id} + {@code type}
 * together — two markers of different concrete types can never be equal even on a
 * coincidental {@code id} collision (practically impossible, but closed by construction
 * rather than left to chance).</p>
 */
public abstract class Marker {

    private final String id;
    private final MarkerType type;
    private final String createdAt;
    private final String lastUpdate;

    protected Marker(String id, MarkerType type, String createdAt, String lastUpdate) {
        this.id = id;
        this.type = type;
        this.createdAt = createdAt;
        this.lastUpdate = lastUpdate;
    }

    public String id() { return id; }
    public MarkerType type() { return type; }
    public String createdAt() { return createdAt; }
    public String lastUpdate() { return lastUpdate; }

    /**
     * Human-readable identity label for this marker — used in conflict messages
     * (e.g. {@code MarkerTypeStrategy#describeConflict}). Never {@code null}.
     */
    public abstract String name();

    /**
     * Extra fields specific to this marker's type, for {@link #toString()} debug
     * output — e.g. {@code repoSlug}/{@code catalogPath} for {@link VaultMarker}.
     * Default: none. Not every type needs to override this.
     */
    protected String typeSpecificFieldsForDebug() {
        return "";
    }

    /**
     * Returns a copy of this marker with {@code lastUpdate} refreshed to
     * {@code now} — every other field, including {@code createdAt}, is
     * preserved. Declared here (not just per-subclass) so {@code MarkerService}
     * can refresh a confirmed marker's timestamp without knowing its concrete
     * type — each subclass returns its own concrete type via a covariant
     * override (e.g. {@code VaultMarker withRefreshedTimestamp(String now)}).
     */
    public abstract Marker withRefreshedTimestamp(String now);

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Marker other)) return false;
        return type == other.type && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type);
    }

    @Override
    public String toString() {
        String result = type.name() + ":" + name();
        String extra = typeSpecificFieldsForDebug();
        if (!extra.isEmpty()) {
            result += "\n" + extra;
        }
        return result;
    }
}
