package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.config.NomadProperties;
import io.aledep10.nomadsync.config.NomadPropertiesLoader;
import io.aledep10.nomadsync.exception.MarkerClaimException;
import io.aledep10.nomadsync.exception.MarkerDeserializationException;
import io.aledep10.nomadsync.marker.*;
import io.aledep10.nomadsync.util.FileUtil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

/**
 * Generic path-protection engine for the entire marker protocol — claims,
 * releases, confirms, and scans reserved marker folders on behalf of any
 * {@link MarkerType}, delegating type-specific behaviour (serialization,
 * conflict messages, same-claimant checks) to the registered
 * {@link MarkerTypeStrategy} for that type.
 *
 * <h2>Nesting protection — asymmetric by design</h2>
 * <p><strong>Ancestor scan</strong> (unbounded, {@link #checkNoNestingConflict})
 * is cross-type and universal: claiming <em>any</em> location checks <em>every</em>
 * {@link MarkerType} while walking upward — a vault must never be claimed inside
 * an already-claimed workspace, and a workspace must never be claimed inside an
 * already-claimed vault, regardless of which type is being claimed right now.
 * This is what actually closes the incident that motivated this entire protocol
 * (a {@code vault relocate} landing on a different workspace's config directory) —
 * a same-type-only scan would not have caught it.</p>
 *
 * <p><strong>Descendant scan</strong> (bounded by {@code marker.maxNestingDepth})
 * is deliberately {@link MarkerType#VAULT}-only: it exists solely to protect
 * against a real Git operation (recursive {@code git add -A}) silently
 * absorbing a foreign vault nested underneath. The other marker types are
 * never subject to that risk (they are always excluded from Git by name,
 * regardless of position), so descending into their subtrees to look for
 * them would add cost without closing any real gap.</p>
 *
 * <h2>{@code marker.maxNestingDepth} — read live, never cached</h2>
 * <p>Resolved from the injected {@link NomadPropertiesLoader} on every
 * {@link #checkNoNestingConflict(String, MarkerType)} call, not stored as a
 * field at construction — a workspace-level override applied mid-bootstrap
 * (e.g. a client's convention requiring a deeper scan than the install
 * default) takes effect for every claim made afterward, without needing this
 * service to be reconstructed.</p>
 */
public class MarkerService {

    private final NomadPropertiesLoader loader;
    private final LogService logService;
    private final Map<MarkerType, MarkerTypeStrategy> strategies;

    /**
     * @param loader     source of {@code marker.*} configuration
     *                   ({@code marker.maxNestingDepth} today, any future
     *                   marker-protocol property later) — read fresh on every
     *                   call that needs it, never cached here at construction
     * @param logService shared logging service
     */
    public MarkerService(NomadPropertiesLoader loader, LogService logService) {
        this.loader = loader;
        this.logService = logService;
        // Built internally, not received as a parameter — this map has exactly
        // one real assembly point in the whole codebase (here), so injecting it
        // from Main's dependency setup would only add ceremony without adding
        // flexibility. Growing the marker protocol to a new active type means
        // adding one line here — linear cost, not combinatorial, same trade-off
        // already accepted for marker.maxNestingDepth staying a single global value.
        this.strategies = Map.of(
                MarkerType.VAULT, new VaultMarkerStrategy(),
                MarkerType.WORKSPACE, new WorkspaceMarkerStrategy());
    }

    /**
     * Verifies that no directory near {@code candidatePath} is already claimed by
     * a marker, using the descendant scan depth read fresh from the loader
     * ({@code marker.maxNestingDepth}, re-read on every call — never cached at
     * construction, so a workspace override applied mid-bootstrap takes effect
     * immediately for every subsequent claim).
     *
     * <ol>
     *   <li>Ancestor scan (unbounded, cross-type) — for every ancestor directory,
     *       check every {@link MarkerType} for a claimed folder. On a hit, describe
     *       the conflict via that type's strategy (best-effort — an unreadable or
     *       unregistered-type marker still blocks the claim, described generically).
     *       Applies regardless of {@code type} — a candidate nested inside any
     *       reserved marker folder is degenerate no matter what it is about to
     *       become.</li>
     *   <li>Descendant scan (bounded, only when {@code type} is
     *       {@link MarkerType#VAULT}) — walk subdirectories up to the configured
     *       depth, skipping (never descending into) any reserved marker folder name
     *       of any type. Report a conflict only for a {@code VAULT} marker found at
     *       depth {@code > 1} (depth 1 would be the candidate's own future claim
     *       slot, out of scope here). Skipped entirely for {@code type == WORKSPACE}:
     *       a workspace's own directory is never absorbed by a recursive
     *       {@code git add -A} the way vault content is — the hazard this scan
     *       protects against doesn't apply to it ({@code NomadSync-MRK-001}).</li>
     * </ol>
     *
     * <p>{@code candidatePath} itself is never checked — an existing marker exactly
     * there is {@link #claim}'s responsibility, enforced atomically at write time.</p>
     *
     * @param candidatePath the path about to be claimed
     * @param type          the marker type being claimed — governs whether the
     *                      descendant scan runs at all
     * @throws MarkerClaimException if any ancestor (any type) or in-range VAULT
     *          descendant is already claimed, or if the descendant scan cannot
     *          complete due to an I/O error
     */
    public void checkNoNestingConflict(String candidatePath, MarkerType type) throws MarkerClaimException {
        checkNoNestingConflict(candidatePath, type, loader.getInt(NomadProperties.Marker.MAX_NESTING_DEPTH, 6));
    }

    /**
     * Same protocol as {@link #checkNoNestingConflict(String, MarkerType)} — ancestor
     * scan cross-type and unbounded, descendant scan bounded and VAULT-only — but
     * with an explicit {@code maxDepth} instead of reading it from the loader.
     * Exists for tests that want to exercise the scanning algorithm itself at a
     * chosen depth without needing a real {@code NomadPropertiesLoader}/workspace
     * override in place.
     *
     * @param candidatePath the path about to be claimed
     * @param type          the marker type being claimed
     * @param maxDepth      descendant scan depth limit, ignored entirely when
     *                      {@code type != MarkerType.VAULT}
     * @throws MarkerClaimException same conditions as
     *          {@link #checkNoNestingConflict(String, MarkerType)}
     */
    public void checkNoNestingConflict(String candidatePath, MarkerType type, int maxDepth)
            throws MarkerClaimException {
        Path candidate = Path.of(candidatePath);

        // ── Ancestor scan (unbounded, cross-type) — unchanged, still applies
        //    to every claim type regardless of `type` ──
        Path ancestor = candidate.getParent();
        while (ancestor != null) {
            Path name = ancestor.getFileName();
            if (name != null) {
                for (MarkerType t : MarkerType.values()) {
                    if (name.toString().equals(t.folderName())) {
                        throw new MarkerClaimException("path '" + candidatePath
                                + "' is nested inside a reserved marker folder itself - "
                                + describeConflictBestEffort(t, ancestor));
                    }
                }
            }
            ancestor = ancestor.getParent();
        }

        // ── Descendant scan (bounded, VAULT claims only) — a WORKSPACE claim
        //    never triggers this: workspace content is excluded from Git by
        //    folder name regardless of position, the hazard this scan protects
        //    against (git add -A absorbing a nested vault) doesn't apply to it.
        if (type == MarkerType.VAULT && Files.isDirectory(candidate)) {
            scanDescendantsForVaultMarker(candidate, /*depth=*/1, maxDepth);
        }
    }

    /**
     * Recursive helper for the descendant half of {@link #checkNoNestingConflict} —
     * see that method's Javadoc for the full rationale (VAULT-only, depth-bounded,
     * skips every reserved folder name regardless of type when recursing).
     */
    private void scanDescendantsForVaultMarker(Path dir, int depth, int maxDepth) throws MarkerClaimException {
        if (depth > maxDepth) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                boolean isVaultMarker = name.equals(MarkerType.VAULT.folderName());
                if (isVaultMarker && depth > 1) {
                       throw new MarkerClaimException("directory '" + child
                               + "' is already claimed - " + describeConflictBestEffort(MarkerType.VAULT, child));
                   }
                   if (isReservedMarkerFolderName(name)) continue;
                   scanDescendantsForVaultMarker(child, depth + 1, maxDepth);
            }
        } catch (IOException e) {
            throw new MarkerClaimException("Unable to scan for nested markers under " + dir, e);
        }
    }

    /**
     * Atomically claims {@code path} for {@code marker} under {@code type} —
     * first delegating to {@link #checkNoNestingConflict(String, MarkerType)},
     * then reserving the exact folder via {@code Files.createDirectory} (atomic,
     * fails if the folder already exists — safe across concurrent processes), then
     * writing the serialized marker (via {@code strategies.get(type).serialize(marker)})
     * into it. On a write failure after a successful folder creation, the
     * reserved-but-empty folder is removed rather than left behind.
     *
     * @throws MarkerClaimException if the path (or a nearby ancestor/descendant)
     *          is already claimed, or if the marker cannot be written
     */
    public void claim(MarkerType type, String path, Marker marker) throws MarkerClaimException {
        checkNoNestingConflict(path, type);
        Path dir = Path.of(path);
        Path folder = markerFolder(dir, type);
        try {
            Files.createDirectory(folder);
        } catch (FileAlreadyExistsException e) {
            throw new MarkerClaimException("path '" + path + "' is already claimed - "
                    + describeConflictBestEffort(type, folder));
        } catch (IOException e) {
            String reason = Files.isDirectory(dir)
                    ? e.getMessage()
                    : "target directory '" + path + "' does not exist";
            throw new MarkerClaimException("Unable to claim path " + path + ": " + reason, e);
        }
        Path descriptor = folder.resolve(MarkerType.DESCRIPTOR_FILE_NAME);
        try {
            Files.writeString(descriptor, strategies.get(type).serialize(marker));
        } catch (IOException e) {
            try { FileUtil.deleteRecursively(folder); } catch (IOException ex) {
                logService.error("Unable to clear marker folder at " + folder + ":"
                        + ex.getMessage(), ex);
            }
            throw new MarkerClaimException("Unable to write marker descriptor at " + descriptor + ": "
                    + e.getMessage(), e);
        }
        logService.info("claim - " + type + " - claimed " + path);
    }

    /**
     * Best-effort removal of the {@code type} marker folder at {@code path} —
     * never throws. No-op if no marker exists there.
     */
    public void release(MarkerType type, String path) {
        Path folder = markerFolder(Path.of(path), type);
        try {
            boolean existed = Files.exists(folder);
            FileUtil.deleteRecursively(folder);
            if (existed) logService.debug("release - removed " + folder);
        } catch (IOException e) {
            logService.warn("release - unable to remove " + folder + ": " + e.getMessage());
        }
    }

    /**
     * "Confirms" an already-registered claimant's ownership of {@code path} by
     * writing or refreshing its {@code type} marker — distinct from {@link #claim},
     * which must atomically fail on any collision. Here the caller is already
     * legitimately registered, so overwriting its own marker is never a conflict —
     * <strong>unless</strong> an existing marker belongs to a different claimant
     * ({@code strategies.get(type).sameClaimant(existing, marker.id())} is false),
     * which indicates real corruption and must not be silently overwritten.
     *
     * <p>Never throws — a failure here degrades gracefully (logged, caller continues),
     * consistent with {@code VaultService#load()}'s existing tolerance for per-vault issues.</p>
     */
    public void refresh(MarkerType type, String path, Marker marker) {
        Path descriptor = markerDescriptor(Path.of(path), type);
        MarkerTypeStrategy strategy = strategies.get(type);
        Marker existing = null;
        try {
            if (Files.exists(descriptor)) {
                existing = strategy.deserialize(Files.readString(descriptor));
            }
        } catch (IOException | MarkerDeserializationException e) {
            logService.warn("refresh - unable to read " + type + " marker at " + descriptor + ": " + e.getMessage());
            return;
        }
        if (existing != null && !strategy.sameClaimant(existing, marker.id())) {
            logService.warn("refresh - " + path + " already marked by a different claimant ("
                    + existing.id() + " vs " + marker.id() + ") - not overwriting");
            return;
        }
        Marker toWrite = (existing == null) ? marker : existing.withRefreshedTimestamp(marker.lastUpdate());
        try {
            Files.createDirectories(descriptor.getParent());
            Files.writeString(descriptor, strategy.serialize(toWrite));
        } catch (IOException e) {
            logService.warn("refresh - unable to write " + type + " marker: " + e.getMessage());
        }
    }

    /**
     * Unconditionally rewrites the descriptor at {@code path} for {@code type} —
     * for legitimate identity transfer (a relocate, where the marker folder
     * itself already exists at the destination and neither {@link #claim} nor
     * {@link #refresh} is the right tool: {@code claim} would fail on the
     * already-existing folder, {@code refresh} would refuse on an id mismatch
     * that is expected here, not a conflict).
     *
     * <p>Preserves the existing descriptor's {@code createdAt} if one is found at
     * {@code path} — {@code marker} is expected to come from a fresh
     * {@code create(...)} call (both timestamps set to "now"), but an overwrite
     * is not a new marker's birth; only {@code lastUpdate} should actually
     * change here. If no existing descriptor is found (unexpected for a real
     * overwrite, but not fatal), {@code marker} is written exactly as given.</p>
     *
     * @param type   the marker type
     * @param path   the already-existing marker folder's parent directory
     * @param marker the replacement marker — its {@code createdAt} is discarded
     *               in favor of the existing descriptor's, if one is found
     * @throws MarkerClaimException if the descriptor cannot be written
     */
    public void overwrite(MarkerType type, String path, Marker marker) throws MarkerClaimException {
        Path descriptor = markerFolder(Path.of(path), type).resolve(MarkerType.DESCRIPTOR_FILE_NAME);

        Marker toWrite = marker;
        if (Files.exists(descriptor)) {
            try {
                Marker existing = strategies.get(type).deserialize(Files.readString(descriptor));
                toWrite = marker.withCreatedAt(existing.createdAt());
            } catch (IOException e) {
                logService.warn("overwrite - unable to read existing descriptor for createdAt preservation at "
                        + descriptor + ": " + e.getMessage());
            }
        }

        try {
            Files.writeString(descriptor, strategies.get(type).serialize(toWrite));
        } catch (IOException e) {
            throw new MarkerClaimException("Unable to write marker descriptor at " + descriptor + ": "
                    + e.getMessage(), e);
        }
        logService.info("overwrite - " + type + " - descriptor rewritten at " + path);
    }

    // ── Path resolution helpers ─────────────────────────────────────────────

    private static Path markerFolder(Path dir, MarkerType type) {
        return dir.resolve(type.folderName());
    }

    private static Path markerDescriptor(Path dir, MarkerType type) {
        return markerFolder(dir, type).resolve(MarkerType.DESCRIPTOR_FILE_NAME);
    }

    private static boolean isReservedMarkerFolderName(String name) {
        return Arrays.stream(MarkerType.values()).anyMatch(t -> t.folderName().equals(name));
    }

    /**
     * Best-effort human-readable description of whoever holds {@code folder}'s
     * marker — reads and deserializes it via {@code type}'s strategy; falls back
     * to a generic description if the marker is missing, unreadable, or the type
     * has no registered strategy. Never throws.
     */
    private String describeConflictBestEffort(MarkerType type, Path folder) {
        try {
            MarkerTypeStrategy strategy = strategies.get(type);
            if (strategy == null) return "an unknown " + type.name().toLowerCase();
            Marker existing = strategy.deserialize(Files.readString(folder.resolve(MarkerType.DESCRIPTOR_FILE_NAME)));
            return strategy.describeConflict(existing);
        } catch (IOException | MarkerDeserializationException e) {
            logService.warn("describeConflictBestEffort - unable to read " + type + " marker at " + folder
                                + ": " + e.getMessage());
            return "an unknown " + type.name().toLowerCase();
        }
    }
}
