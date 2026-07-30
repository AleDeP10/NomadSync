package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.config.NomadProperties;
import io.aledep10.nomadsync.exception.MarkerClaimException;
import io.aledep10.nomadsync.exception.MarkerDeserializationException;
import io.aledep10.nomadsync.marker.*;
import io.aledep10.nomadsync.util.FileUtil;
import io.aledep10.nomadsync.util.PropertiesUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

// VaultMarkerStrategy and WorkspaceMarkerStrategy already live in this same
// package (io.aledep10.nomadsync.marker) — no import needed for either.

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
 * <p><strong>Descendant scan</strong> (bounded by {@code maxDepth}) is
 * deliberately {@link MarkerType#VAULT}-only: it exists solely to protect
 * against a real Git operation (recursive {@code git add -A}) silently
 * absorbing a foreign vault nested underneath. The other marker types are
 * never subject to that risk (they are always excluded from Git by name,
 * regardless of position), so descending into their subtrees to look for
 * them would add cost without closing any real gap.</p>
 *
 * <h2>Constructor argument order</h2>
 * <p>Follows the project convention: {@link Properties} first, {@link LogService}
 * last — no dependencies in between, since the strategy map is built internally
 * (see the constructor's own Javadoc).</p>
 *
 * <p>Skeleton only — every method body is a placeholder pending the GREEN step.</p>
 */
public class MarkerService {

    private final LogService logService;
    private final Map<MarkerType, MarkerTypeStrategy> strategies;
    private final int maxNestingDepth;

    /**
     * @param properties  application properties — may contain
     *                    {@code marker.maxNestingDepth} (default 6), used only by
     *                    the no-argument {@link #checkNoNestingConflict(String)}
     *                    overload's descendant scan
     * @param logService  shared logging service
     */
    public MarkerService(Properties properties, LogService logService) {
        this.logService = logService;
        this.maxNestingDepth = PropertiesUtil.getInt(properties, NomadProperties.Marker.MAX_NESTING_DEPTH, 6);
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
     * Convenience overload — uses the depth configured at construction time
     * (from {@code marker.maxNestingDepth}). See {@link #checkNoNestingConflict(String, int)}
     * for the full contract.
     */
    public void checkNoNestingConflict(String candidatePath) throws MarkerClaimException {
        checkNoNestingConflict(candidatePath, this.maxNestingDepth);
    }

    /**
     * Verifies that no directory near {@code candidatePath} is already claimed by
     * a marker of any type.
     *
     * <ol>
     *   <li>Ancestor scan (unbounded, cross-type) — for every ancestor directory,
     *       check every {@link MarkerType} for a claimed folder. On a hit, describe
     *       the conflict via that type's strategy (best-effort — an unreadable or
     *       unregistered-type marker still blocks the claim, described generically).</li>
     *   <li>Descendant scan (bounded by {@code maxDepth}, {@link MarkerType#VAULT}
     *       only) — walk subdirectories up to {@code maxDepth} levels, skipping
     *       (never descending into) any reserved marker folder name of any type.
     *       Report a conflict only for a {@code VAULT} marker found at depth {@code > 1}
     *       (depth 1 would be the candidate's own future claim slot, out of scope here).</li>
     * </ol>
     *
     * <p>{@code candidatePath} itself is never checked — an existing marker exactly
     * there is {@link #claim}'s responsibility, enforced atomically at write time.</p>
     *
     * @throws MarkerClaimException if any ancestor (any type) or in-range VAULT
     *          descendant is already claimed, or if the descendant scan cannot
     *          complete due to an I/O error
     */
    public void checkNoNestingConflict(String candidatePath, int maxDepth) throws MarkerClaimException {
        Path candidate = Path.of(candidatePath);

        // ── Ancestor scan (unbounded, cross-type) ──
        Path ancestor = candidate.getParent();
        while (ancestor != null) {
            for (MarkerType type : MarkerType.values()) {
                Path folder = markerFolder(ancestor, type);
                if (Files.isDirectory(folder)) {
                    throw new MarkerClaimException("path '" + candidatePath
                            + "' is nested inside a directory already claimed - "
                            + describeConflictBestEffort(type, folder) + " (" + ancestor + ")");
                }
            }
            ancestor = ancestor.getParent();
        }

        // ── Descendant scan (bounded, VAULT-only) ──
        if (Files.isDirectory(candidate)) {
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
     * first delegating to {@link #checkNoNestingConflict(String)}, then reserving
     * the exact folder via {@code Files.createDirectory} (atomic, fails if the
     * folder already exists — safe across concurrent processes), then writing
     * the serialized marker (via {@code strategies.get(type).serialize(marker)})
     * into it. On a write failure after a successful folder creation, the
     * reserved-but-empty folder is removed rather than left behind.
     *
     * @throws MarkerClaimException if the path (or a nearby ancestor/descendant)
     *          is already claimed, or if the marker cannot be written
     */
    public void claim(MarkerType type, String path, Marker marker) throws MarkerClaimException {
        checkNoNestingConflict(path);
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
     * Unconditionally (re)writes the marker descriptor for an already-existing
     * marker folder, regardless of the current claimant on disk — unlike
     * {@link #refresh}, never compares {@code sameClaimant}; unlike {@link #claim},
     * never creates the folder (must already exist). Reserved for legitimate
     * identity-transfer operations where the marker's own id is derived from its
     * path and therefore must change together with it (e.g. a workspace relocate).
     *
     * @throws MarkerClaimException if no marker folder exists at {@code path}, or
     *                               if the descriptor cannot be written
     */
    public void overwrite(MarkerType type, String path, Marker marker) throws MarkerClaimException {
        Path folder = markerFolder(Path.of(path), type);
        if (!Files.isDirectory(folder)) {
            throw new MarkerClaimException("path '" + path + "' has no existing marker folder to overwrite");
        }
        Path descriptor = folder.resolve(MarkerType.DESCRIPTOR_FILE_NAME);
        try {
            Files.writeString(descriptor, strategies.get(type).serialize(marker));
        } catch (IOException e) {
            throw new MarkerClaimException("Unable to overwrite marker descriptor at " + descriptor + ": " + e.getMessage(), e);
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
