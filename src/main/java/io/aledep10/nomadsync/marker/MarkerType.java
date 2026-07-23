package io.aledep10.nomadsync.marker;

/**
 * The reserved marker folders that make up NomadSync's path-protection
 * protocol. Each {@link #folderName()} is a hidden directory NomadSync creates
 * inside a real filesystem location to atomically claim it — see
 * {@code MarkerService} for the shared claim/scan/release/refresh machinery,
 * and each type's own section below for what exactly it protects.
 *
 * <h2>Why a reserved folder, not a reserved file</h2>
 * <p>A folder (rather than a single marker file) allows a claimed location to
 * host more than one piece of metadata — a JSON descriptor
 * ({@link #DESCRIPTOR_FILE_NAME}), and potentially nested content in the future —
 * without ever needing a second, differently-named marker at the same location.</p>
 *
 * <h2>Common descriptor contract</h2>
 * <p>Every claimed folder contains exactly one file, {@link #DESCRIPTOR_FILE_NAME} —
 * the folder name itself already disambiguates which {@code MarkerType} it is,
 * so the descriptor's own filename never needs to repeat that information.</p>
 *
 * <h2>Nesting protection</h2>
 * <p>Every reserved folder name is skipped during descendant traversal
 * regardless of type — a marker is never mistaken for a foreign conflict when
 * simply walked past, nor are its contents ever recursed into. Reporting an
 * actual conflict (throwing when a claimed folder is found) during the
 * ancestor scan is cross-type and universal — claiming any location checks
 * for the presence of any {@code MarkerType} while walking upward. The bounded
 * descendant scan, by contrast, only reports {@link #VAULT} — the only type
 * whose content is ever at risk of being absorbed by a recursive
 * {@code git add}; the other type is excluded from Git by folder name
 * regardless of position, so descending into it looking for conflicts would
 * add cost without closing any real gap.</p>
 */
public enum MarkerType {

    /**
     * Protects an individual vault's content directory — the root folder a
     * single registered {@code Vault} points to.
     *
     * <p>Guards against: two vaults claiming the same or an overlapping
     * directory tree, which would let a Git operation on one silently absorb
     * the other's files.</p>
     */
    VAULT(".nomadsync-vault", "VLT"),

    /**
     * Protects a workspace's home directory — the folder containing an
     * adjacent {@code config.properties}/{@code catalog.json} pair, plus the
     * workspace's own descriptor.
     *
     * <p>Guards against: a vault being created or relocated directly on top of —
     * or nested inside — a workspace's own home, which would either destroy
     * that workspace's configuration or silently absorb it into a vault's Git
     * history.</p>
     *
     * <p>Path resolution is logical: vault paths registered under a workspace
     * remain absolute and may live anywhere on the filesystem — a workspace's
     * home directory does not need to physically contain them. When
     * {@code path.catalog} resolves within the same folder as the workspace's
     * own descriptor, it is constrained to a bare filename (no path
     * separators) — a value that could point elsewhere is rejected outright,
     * rather than relying on a runtime check against every other known
     * workspace.</p>
     */
    WORKSPACE(".nomadsync-workspace", "WKS");

    private final String folderName;
    private final String code;

    MarkerType(String folderName, String code) {
        this.folderName = folderName;
        this.code = code;
    }

    /**
     * The reserved directory name this marker type claims, e.g.
     * {@code ".nomadsync-vault"}. Always a single path component — never
     * nested, never containing a separator.
     */
    public String folderName() {
        return folderName;
    }

    /**
     * Short prefix identifying this type in {@link Marker#name()} — e.g.
     * {@code "VLT"}/{@code "WKS"}. Prevents a naming collision between a
     * vault and a workspace that happen to share the same human-readable
     * identifier (e.g. a vault named {@code "ToDoList"} and a workspace also
     * named {@code "ToDoList"}) — without this prefix, both would produce the
     * identical string from {@code name()} in any context that reads it
     * without knowing the marker's concrete type in advance.
     */
    public String code() {
        return code;
    }

    /**
     * Filename of the JSON descriptor inside every reserved folder, regardless
     * of {@code MarkerType} — the folder name already disambiguates the kind,
     * so the file inside does not need to repeat it.
     */
    public static final String DESCRIPTOR_FILE_NAME = "descriptor.json";
}