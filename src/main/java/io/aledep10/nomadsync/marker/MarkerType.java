package io.aledep10.nomadsync.marker;

/**
 * The six reserved marker folders that make up NomadSync's path-protection
 * protocol. Each {@link #folderName()} is a hidden directory NomadSync creates
 * inside a real filesystem location to atomically "claim" it — see
 * {@code MarkerService} for the shared claim/scan/release/refresh machinery,
 * and each type's own section below for what exactly it protects.
 *
 * <h2>Why a reserved folder, not a reserved file</h2>
 * <p>A folder (rather than a single marker file, the original design used only
 * for {@link #VAULT} before this protocol existed) allows a claimed location to
 * host more than one piece of metadata — a JSON descriptor today
 * ({@link #DESCRIPTOR_FILE_NAME}), and potentially nested content in the future
 * (e.g. a per-vault {@link #BACKUPS} override living inside that vault's own
 * {@link #VAULT} folder) — without ever needing a second, differently-named
 * marker at the same location.</p>
 *
 * <h2>Common descriptor contract</h2>
 * <p>Every claimed folder contains exactly one file, {@link #DESCRIPTOR_FILE_NAME} —
 * the folder name itself already disambiguates which {@code MarkerType} it is,
 * so the descriptor's own filename never needs to repeat that information.
 * The descriptor's <em>shape</em> (which fields it carries) is type-specific —
 * see {@code MarkerService}'s design notes for whether a single generic
 * descriptor record can serve all six types, or whether each type needs its
 * own.</p>
 *
 * <h2>Nesting protection is symmetric across all six types</h2>
 * <p>Regardless of which types are actively claimed at any given point in the
 * codebase's evolution, <strong>every</strong> reserved folder name is already
 * skipped during descendant traversal by {@code VaultService}'s current nesting
 * scan — a not-yet-implemented type can never be misidentified as a foreign
 * conflict, nor can its future contents be mistakenly walked into. Only the
 * <em>reporting</em> of a conflict (throwing when a claimed folder is actually
 * found) is currently specific to {@link #VAULT}, pending the same treatment
 * for the other five once their own claim logic exists.</p>
 */
public enum MarkerType {

    /**
     * Protects an individual vault's content directory — the root folder a
     * single registered {@code Vault} points to.
     *
     * <p>Guards against: two vaults (possibly from different, never-simultaneously-loaded
     * {@code catalog.json} files) claiming the same or an overlapping directory tree,
     * which would let a Git operation on one silently absorb the other's files.</p>
     */
    VAULT(".nomadsync-vault"),

    /**
     * Protects a workspace's home directory — the folder containing a
     * {@code config.properties}/{@code catalog.json} pair that are adjacent
     * (the common case; a workspace whose config and catalog live in separate
     * locations is a distinct, more complex scenario — see {@link #CONFIG} and
     * {@link #CATALOG}).
     *
     * <p>Guards against: a vault being created or relocated directly on top of —
     * or nested inside — a workspace's own home, which would either destroy that
     * workspace's configuration or silently absorb it into a vault's Git history.
     * This is the exact incident that originally motivated the entire marker
     * protocol (a {@code vault relocate} aimed, by a missing {@code =}, at a
     * different workspace's config directory).</p>
     *
     * <p>Deliberately <strong>logical only</strong> — vault paths registered under
     * a workspace remain absolute and may live anywhere on the filesystem; the
     * workspace's home directory does not need to physically contain them. A
     * "physical" mode (vault paths relative to the workspace root, requiring
     * every vault to live underneath it) was evaluated and explicitly shelved:
     * no real usage scenario requires it, and {@code vault relocate} already
     * covers the one case (moving something) that would otherwise motivate it.</p>
     */
    WORKSPACE(".nomadsync-workspace"),

    /**
     * Protects a directory containing one or more {@code config.properties}
     * files, specifically for the case where configuration is <strong>not</strong>
     * adjacent to its corresponding catalog (see {@link #WORKSPACE} for the
     * common, adjacent case, which does not need this type at all).
     *
     * <p>Guards against: the same class of incident as {@link #WORKSPACE}, scoped
     * to configuration files that live in a shared location serving multiple
     * catalogs — e.g. several {@code owner.properties} files (each holding
     * credentials for a different GitHub account) stored together, separately
     * from any of the catalogs they configure.</p>
     */
    CONFIG(".nomadsync-config"),

    /**
     * Protects a directory containing a {@code catalog.json}, specifically for
     * the case where the catalog is <strong>not</strong> adjacent to its
     * configuration (mirror image of {@link #CONFIG} — see {@link #WORKSPACE}
     * for the common, adjacent case).
     *
     * <p>Guards against: the registry file itself — which lists every vault's
     * absolute path and owner/name — being accidentally absorbed into a vault's
     * Git history, exposing the full map of a workspace's vaults if committed
     * and pushed.</p>
     */
    CATALOG(".nomadsync-catalog"),

    /**
     * Protects a directory holding local backup snapshots (see
     * {@code VaultService#makeVaultSnapshot}) — content that must never be
     * persisted to a remote Git repository, since it exists solely to protect
     * against local mistakes and has no meaning once copied elsewhere.
     *
     * <p>Two scopes are anticipated: a workspace-wide default (all vaults in a
     * workspace share one backups root, today's actual behaviour via
     * {@code path.backups}) and a future per-vault override (a
     * {@link #BACKUPS} folder nested inside that specific vault's own
     * {@link #VAULT} folder, for a vault that needs different retention or
     * storage policy than its workspace's default — the originally discussed
     * "ForgeBook" scenario of per-project backup policy).</p>
     */
    BACKUPS(".nomadsync-backups"),

    /**
     * Protects a directory holding unresolved sync-conflict files (see
     * {@code VaultService#saveConflict}) — like {@link #BACKUPS}, local-only
     * content that must never reach a remote Git repository.
     *
     * <p>Same workspace-wide-default / per-vault-override duality anticipated
     * as {@link #BACKUPS}, for the same reasons.</p>
     */
    CONFLICTS(".nomadsync-conflicts");

    private final String folderName;

    MarkerType(String folderName) {
        this.folderName = folderName;
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
     * Filename of the JSON descriptor inside every reserved folder, regardless
     * of {@code MarkerType} — the folder name already disambiguates the kind,
     * so the file inside does not need to repeat it.
     */
    public static final String DESCRIPTOR_FILE_NAME = "descriptor.json";
}