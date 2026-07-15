package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.config.NomadProperties;
import io.aledep10.nomadsync.exception.VaultException;
import io.aledep10.nomadsync.exception.VaultIntegrityException;
import io.aledep10.nomadsync.exception.VaultParseException;
import io.aledep10.nomadsync.gitignore.exception.GitignoreException;
import io.aledep10.nomadsync.marker.MarkerType;
import io.aledep10.nomadsync.vault.Vault;
import io.aledep10.nomadsync.util.*;
import io.aledep10.nomadsync.vault.VaultMarker;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the lifecycle of registered {@link Vault} instances — load, persist, and CRUD.
 *
 * <p>Vaults are stored in memory as a {@link HashMap} keyed by UUID for O(1) lookup.
 * Every mutation (create, update, delete) is immediately persisted to {@code catalog.json}
 * via {@link JsonMapper} to guarantee consistency between memory and disk.</p>
 *
 * <h2>Snapshot paths</h2>
 * <p>Backup and conflict directories can be configured explicitly via
 * {@code path.backups} and {@code path.conflicts} in the application properties.
 * If those keys are absent (or relative), both are resolved via
 * {@link PropertiesUtil#resolvePath} against {@code configDir} — the directory
 * containing the {@code config.properties} file actually in use for this run —
 * never against the process's working directory. This keeps a workspace
 * self-contained regardless of where a command is invoked from.</p>
 *
 * <h2>Vault identity and uniqueness constraints</h2>
 * <p>Each vault is uniquely identified by its {@code repoSlug} — the composite
 * {@code <owner>/<name>} string derived from {@link Vault#getRepoSlug()}. This
 * mirrors GitHub's own uniqueness guarantee: two repositories on different accounts
 * may share the same {@code name}, but the {@code owner/name} combination is always
 * globally unique.</p>
 *
 * <p>Additionally, no two vaults may point to the same local {@code path}, nor may
 * one vault's path be nested inside (or contain) another's — either would let a
 * single Git operation on one vault silently absorb files belonging to another.</p>
 *
 * <p>{@link #load()}, {@link #create(String, String, String)} and {@link #update(Vault)}
 * all enforce both constraints by throwing {@link VaultException} on violation.</p>
 *
 * <h2>Marker-based path protection</h2>
 * <p>Beyond the in-memory uniqueness checks above (which only see vaults already
 * loaded in the current session), every vault's directory is marked on disk with a
 * reserved {@code .nomadsync-vault/} folder — see {@link #claimVaultPath},
 * {@link #checkNoNestingConflict}, {@link #releaseVaultMarker}, and
 * {@link #refreshVaultMarker}. This catches conflicts with vaults belonging to a
 * <em>different</em> workspace never loaded in this session (e.g. two independent
 * {@code catalog.json} files whose directory trees happen to overlap), which the
 * in-memory checks alone cannot see.</p>
 *
 * <h2>Defensive copies</h2>
 * <p>{@link #findAll()} returns a new {@link ArrayList} on every call — structural
 * modifications to the returned list do not affect the internal map. Note: the
 * {@link Vault} objects themselves are shared references — mutate via
 * {@link #update(Vault)} to persist changes.</p>
 *
 * <h2>Constructor argument order</h2>
 * <p>Follows the project convention: {@link Properties} first, dependencies in
 * descending order of complexity, {@link LogService} last.</p>
 *
 * <h2>Logging conventions</h2>
 * <p>A single {@code INFO}-level log line is emitted at the <em>start</em> of
 * every mutating operation (one that changes the in-memory registry or writes
 * to disk) — identifying the target and the action about to be performed.
 * Internal detail (e.g. how many vaults a {@link #load()} call actually read)
 * is logged at {@code DEBUG}. Pure queries ({@link #findAll()}, {@link #findById},
 * {@link #findByRepoSlug}, {@link #findAllByName}, {@link #findByPath}) emit no
 * log at all — they answer a question without changing anything.</p>
 * <p>Note: {@link #save()} is itself a mutation and logs its own intro line —
 * since {@link #create}, {@link #update}, and {@link #delete} all call it
 * internally, each of those operations produces two {@code INFO} lines per
 * call (its own, plus {@code save()}'s), not one. This is intentional: each
 * line documents a real, distinct write.</p>
 */
public class VaultService {

    /** Package-private for test assertions on file existence. */
    final File catalogFile;

    private final Map<String, Vault> vaults = new HashMap<>();
    private final GitignoreService gitignoreService;
    private final LogService logService;
    private final Path backupsRoot;
    private final Path conflictsRoot;
    private final int maxNestingDepth;

    /**
     * Constructs the service. Does not load from disk — call {@link #load()} explicitly.
     *
     * <p>{@code path.catalog}, {@code path.backups}, and {@code path.conflicts} are all
     * resolved via {@link PropertiesUtil#resolvePath} against {@code configDir} — the
     * directory containing the {@code config.properties} file actually in use for
     * this run, not the process's working directory nor the location of
     * {@code NomadSync.jar}. This keeps a workspace self-contained: a client's own
     * {@code config.properties}, {@code catalog.json}, log file, and (by default)
     * its backups/conflicts all live together, addressed relative to wherever that
     * workspace's config file lives. Absent, blank, or relative values are all
     * resolved uniformly; an already-absolute value is left untouched.</p>
     *
     * @param properties       application properties, optionally containing
     *                         {@code path.catalog}, {@code path.backups},
     *                         {@code path.conflicts}
     * @param configDir        directory containing the {@code config.properties}
     *                         file in use — base for resolving all three path
     *                         properties above when relative or absent
     * @param gitignoreService used to read active ignore patterns during snapshot creation
     * @param logService       shared logging service
     */
    public VaultService(Properties properties, Path configDir,
                        GitignoreService gitignoreService,
                        LogService logService) {
        this.catalogFile = PropertiesUtil.resolvePath(properties, NomadProperties.Path.CATALOG,
                "catalog.json", configDir, logService).toFile();
        this.gitignoreService = gitignoreService;
        this.logService       = logService;

        this.backupsRoot = PropertiesUtil.resolvePath(properties, NomadProperties.Path.BACKUPS,
                "backups", configDir, logService);
        this.conflictsRoot = PropertiesUtil.resolvePath(properties, NomadProperties.Path.CONFLICTS,
                "remote-conflicts", configDir, logService);

        this.maxNestingDepth = PropertiesUtil.getInt(properties, NomadProperties.Path.MAX_NESTING_DEPTH, 6);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Loads all vaults from {@code catalog.json} and replaces the current in-memory state.
     *
     * <p>If the file does not exist, the in-memory state is cleared and an empty list
     * is returned — no exception is thrown.</p>
     *
     * <p>Two validations are run before the in-memory state is replaced:</p>
     * <ol>
     *   <li>No two vaults share the same {@code repoSlug} ({@code <owner>/<name>}).</li>
     *   <li>No two vaults share the same local {@code path}.</li>
     * </ol>
     * <p>If either validation fails, the in-memory state is left unchanged and a
     * {@link VaultException} is thrown — the caller can inspect the exception message
     * to identify the offending value.</p>
     *
     * <p>After the in-memory state is replaced, every loaded vault's
     * {@code .nomadsync-vault} marker is confirmed/refreshed via
     * {@link #refreshVaultMarker} — see that method for why this is a "confirm",
     * distinct from the atomic "claim" performed by {@link #claimVaultPath}.</p>
     *
     * <p>Logging: a single {@code INFO} line announces the operation at the start;
     * the number of vaults actually loaded is logged at {@code DEBUG} on success.</p>
     *
     * @return the list of loaded vaults
     * @throws VaultException if two or more vaults share the same {@code repoSlug} or {@code path},
     *                        or if the file exists but cannot be read or parsed
     */
    public List<Vault> load() throws VaultException {
        logService.info("load - loading vault registry from " + catalogFile.getPath());
        List<Vault> loaded;
        try {
            loaded = JsonMapper.loadVaultsFromFile(catalogFile);
        } catch (IOException e) {
            throw new VaultParseException("Unable to parse the vault file: " + e.getMessage(), e);
        }
        validateUniqueRepoSlugs(loaded);
        validateUniquePaths(loaded);
        vaults.clear();
        loaded.forEach(v -> vaults.put(v.getId(), v));
        logService.debug("load - " + vaults.size() + " vault(s) loaded");

        for (Vault v : vaults.values()) {
            refreshVaultMarker(v);
        }

        return new ArrayList<>(vaults.values());
    }

    /**
     * Persists the current in-memory vault state to {@code catalog.json}.
     *
     * <p>Called automatically by all mutating operations. Can be called explicitly
     * if external mutations to {@link Vault} objects need to be flushed to disk.</p>
     *
     * <p>Logging: a single {@code INFO} line announces the operation at the start —
     * see the class-level note on the resulting double log line when called from
     * {@link #create}, {@link #update}, or {@link #delete}.</p>
     *
     * @throws VaultException if the file cannot be written
     */
    public void save() throws VaultException {
        logService.info("save - persisting " + vaults.size() + " vault(s) to " + catalogFile.getPath());
        try {
            JsonMapper.saveVaultsToFile(catalogFile, new ArrayList<>(vaults.values()));
        } catch (IOException e) {
            throw new VaultException("Failed to persist vault: " + e.getMessage(), e);
        }
    }

    /**
     * Validates that no two vaults in the given list share the same {@code repoSlug}.
     *
     * <p>Two vaults with the same {@code name} but different {@code owner}s —
     * e.g. {@code "Alice/portfolio"} and {@code "Bob/portfolio"} —
     * are distinct and explicitly allowed.</p>
     *
     * <p>Internal step of {@link #load()} — no log of its own; violations surface
     * as a thrown exception, and {@code load()}'s own intro line already covers
     * observability for this step.</p>
     *
     * @param loaded vaults freshly deserialised from {@code catalog.json}
     * @throws VaultException if any {@code repoSlug} appears more than once
     */
    private void validateUniqueRepoSlugs(List<Vault> loaded) throws VaultException {
        Set<String> seen = new HashSet<>();
        for (Vault vault : loaded) {
            if (!seen.add(vault.getRepoSlug())) {
                throw new VaultIntegrityException(
                        "duplicated repoSlug in catalog.json: " + vault.getRepoSlug());
            }
        }
    }

    /**
     * Validates that no two vaults in the given list share the same local {@code path}.
     *
     * <p>Two orchestrators operating on the same local Git repository concurrently
     * would produce undefined behaviour — this constraint prevents that scenario
     * from being registered in the first place.</p>
     *
     * <p>Internal step of {@link #load()} — no log of its own; violations surface
     * as a thrown exception, and {@code load()}'s own intro line already covers
     * observability for this step.</p>
     *
     * @param loaded vaults freshly deserialised from {@code catalog.json}
     * @throws VaultException if any {@code path} appears more than once
     */
    private void validateUniquePaths(List<Vault> loaded) throws VaultException {
        Set<String> seen = new HashSet<>();
        for (Vault vault : loaded) {
            if (!seen.add(vault.getPath())) {
                throw new VaultIntegrityException(
                        "duplicated path in catalog.json: " + vault.getPath());
            }
        }
    }

    // ── Vault marker lifecycle (.nomadsync-vault) ──────────────────────────────
    //
    // Every vault directory carries a reserved .nomadsync-vault/descriptor.json
    // folder, claimed atomically at creation and confirmed/refreshed on every
    // load(). This section groups the full lifecycle together: the nesting
    // pre-check, the atomic claim, release on removal/relocation, and the
    // opportunistic confirm-on-load — plus the small private helpers shared
    // across all four. Kept as one block since this is the area most likely to
    // grow next (workspace/config/catalog markers follow the same shape).

    /**
     * Verifies that no directory near {@code candidatePath} is already claimed by
     * another vault — checked in two directions:
     * <ul>
     *   <li>Ancestors of {@code candidatePath} (unbounded upward scan) — catches
     *       placing a vault inside a directory already claimed higher up.</li>
     *   <li>Descendants of {@code candidatePath}, up to {@link #maxNestingDepth}
     *       levels ({@link NomadProperties.Path#MAX_NESTING_DEPTH}, default 6) —
     *       catches placing a vault around a directory already claimed deeper down.</li>
     * </ul>
     *
     * <p>{@code candidatePath} itself is never checked here — an existing marker
     * exactly at that path is {@link #claimVaultPath}'s responsibility, enforced
     * atomically at write time, not by this pre-check.</p>
     *
     * <p>Tolerates a {@code candidatePath} that does not yet exist on disk — the
     * descendant scan is simply skipped in that case, since there is nothing to
     * walk.</p>
     *
     * @param candidatePath absolute path to validate
     * @throws VaultException if any ancestor or in-range descendant already
     *                          carries a {@code .nomadsync-vault} marker folder,
     *                          or if the descendant scan cannot complete due to
     *                          an I/O error
     */
    public void checkNoNestingConflict(String candidatePath) throws VaultException {
        Path candidate = Path.of(candidatePath);

        // Ancestor scan (unbounded)
        Path ancestor = candidate.getParent();
        while (ancestor != null) {
            Path folder = markerFolder(ancestor, MarkerType.VAULT);
            if (Files.isDirectory(folder)) {
                String holder = readHolderRepoSlugBestEffort(folder);
                throw new VaultException("path '" + candidatePath + "' is nested inside a directory "
                        + "already claimed by vault '" + holder + "' (" + ancestor + ")");
            }
            ancestor = ancestor.getParent();
        }

        // Descendant scan (bounded by maxNestingDepth)
        if (Files.isDirectory(candidate)) {
            scanDescendantsForMarker(candidate, /*depth=*/1);
        }
    }

    /**
     * Atomically claims {@code vault.getPath()} by creating its reserved
     * {@code .nomadsync-vault/} marker folder — first delegating to
     * {@link #checkNoNestingConflict} (ancestor/descendant scan), then reserving
     * the exact path via {@link Files#createDirectory} (atomic at the filesystem
     * level: fails if the folder already exists, safe across concurrent
     * processes, not just threads — the same pattern used by snapshot directory
     * naming).
     *
     * <p>A second claim attempt on a path already claimed — even by the same
     * vault — always fails. Re-confirming an existing marker is
     * {@link #load()}'s responsibility, not this method's; claiming is a one-time
     * event per directory.</p>
     *
     * @param vault the vault whose path is being claimed
     * @throws VaultException if the path (or a nearby ancestor/descendant) is
     *                          already claimed, or if the marker folder cannot
     *                          be created or written to
     */
    public void claimVaultPath(Vault vault) throws VaultException {
        checkNoNestingConflict(vault.getPath());

        Path folder = markerFolder(Path.of(vault.getPath()), MarkerType.VAULT);
        try {
            Files.createDirectory(folder);   // atomic — throws FileAlreadyExistsException if taken
        } catch (FileAlreadyExistsException e) {
            String holder = readHolderRepoSlugBestEffort(folder);
            throw new VaultException("path '" + vault.getPath()
                    + "' is already claimed by vault '" + holder + "'");
        } catch (IOException e) {
            throw new VaultException("Unable to claim vault path " + vault.getPath() + ": " + e.getMessage(), e);
        }

        Path descriptor = folder.resolve(MarkerType.DESCRIPTOR_FILE_NAME);
        String now = DateFormats.nowLog();
        VaultMarker marker = VaultMarker.create(vault.getId(), vault.getRepoSlug(), catalogFile.getPath(), now);
        try {
            JsonMapper.saveVaultMarkerToFile(descriptor.toFile(), marker);
        } catch (IOException e) {
            // rollback: remove the whole reserved folder, not just the descriptor —
            // an empty .nomadsync-vault/ left behind would itself look claimed
            try {
                FileUtil.deleteRecursively(folder);
            } catch (IOException ex) {
                logService.error("Unable to remove reserved-but-empty marker folder " + folder
                        + " for " + marker.repoSlug() + ": " + ex.getMessage(), ex);
            }
            throw new VaultException("Unable to write vault descriptor at " + descriptor + ": " + e.getMessage(), e);
        }
        logService.info("claimVaultPath - " + vault.getRepoSlug() + " - claimed " + vault.getPath());
    }

    /**
     * Best-effort removal of the {@code .nomadsync-vault} marker folder at
     * {@code path} — never throws. Used when a vault's path changes
     * ({@link #update}) or when a vault is removed ({@link #delete}): only the
     * metadata marker is cleaned up, the physical directory and its contents are
     * never touched, consistent with {@code vault remove}'s existing
     * "registration only" contract.
     *
     * @param path the (former) vault path whose marker folder should be released
     */
    private void releaseVaultMarker(String path) {
        Path folder = markerFolder(Path.of(path), MarkerType.VAULT);
        try {
            boolean existed = Files.exists(folder);
            FileUtil.deleteRecursively(folder);   // no-op if it doesn't exist
            if (existed) {
                logService.debug("releaseVaultMarker - removed " + folder);
            }
        } catch (IOException e) {
            logService.warn("releaseVaultMarker - unable to remove " + folder + ": " + e.getMessage());
        }
    }

    /**
     * "Confirms" ownership of an already-registered vault's directory by writing
     * or refreshing its {@code .nomadsync-vault} marker — distinct from
     * "claiming" a directory ({@link #claimVaultPath}, used by
     * create/add/relocate), which must atomically fail on collision. Here the
     * vault is already legitimately registered, so overwriting its own marker is
     * never a conflict — <strong>unless</strong> an existing marker belongs to a
     * different vault id, which indicates real corruption and must not be
     * silently overwritten.
     *
     * <p>Never throws — a failure here degrades gracefully (logged, {@link #load()}
     * continues), consistent with {@code load()}'s existing tolerance for
     * per-vault issues.</p>
     *
     * @param vault the already-registered vault whose marker should be confirmed
     */
    private void refreshVaultMarker(Vault vault) {
        Path descriptor = markerDescriptor(Path.of(vault.getPath()), MarkerType.VAULT);

        VaultMarker existing;
        try {
            existing = JsonMapper.loadVaultMarkerFromFile(descriptor.toFile());
        } catch (IOException e) {
            logService.warn("refreshVaultMarker - " + vault.getRepoSlug()
                    + " - unable to read .nomadsync-vault marker: " + e.getMessage());
            return;
        }

        if (existing != null && !existing.id().equals(vault.getId())) {
            logService.warn("refreshVaultMarker - " + vault.getRepoSlug()
                    + " - path already marked by a different vault (id=" + existing.id()
                    + ", repoSlug=" + existing.repoSlug() + ") - not overwriting, possible conflict");
            return;
        }

        String now = DateFormats.nowLog();
        VaultMarker marker = (existing == null)
                ? VaultMarker.create(vault.getId(), vault.getRepoSlug(), catalogFile.getPath(), now)
                : existing.withRefreshedTimestamp(now);

        try {
            // The .nomadsync-vault folder may not exist yet — this happens for a
            // vault confirmed by load() that was never claimed via claimVaultPath()
            // (e.g. registered by directly editing catalog.json, or migrated from
            // before this feature existed). Without this, such a vault would fail
            // silently on every single load() forever, never actually protected.
            Files.createDirectories(descriptor.getParent());
            JsonMapper.saveVaultMarkerToFile(descriptor.toFile(), marker);
        } catch (IOException e) {
            logService.warn("refreshVaultMarker - " + vault.getRepoSlug()
                    + " - unable to write .nomadsync-vault marker: " + e.getMessage());
        }
    }

    /**
     * Recursive helper for {@link #checkNoNestingConflict} — walks {@code dir}'s
     * immediate subdirectories, then recurses, stopping once {@code depth}
     * exceeds {@link #maxNestingDepth}. Depth 1 = direct children of the original
     * candidate.
     *
     * <p>Two decisions are kept deliberately separate for each child found:</p>
     * <ul>
     *   <li><strong>Report</strong> — throw if the child is named
     *       {@link MarkerType#VAULT}'s reserved folder, <em>except</em> at
     *       {@code depth == 1}, where it would be the original candidate's own
     *       (not-yet-claimed) marker slot — checking that is
     *       {@link #claimVaultPath}'s job via its atomic
     *       {@link Files#createDirectory}, not this pre-check's.</li>
     *   <li><strong>Recurse</strong> — never descend into <em>any</em> reserved
     *       marker folder (own or foreign, {@code VAULT} or a future type),
     *       regardless of whether it was reported — its contents (a descriptor,
     *       or a future nested backups folder) are never meaningful to scan
     *       further.</li>
     * </ul>
     *
     * @throws VaultException if a marker is found within range (beyond the
     *                          candidate's own depth-1 slot), or if the
     *                          directory tree cannot be read
     */
    private void scanDescendantsForMarker(Path dir, int depth) throws VaultException {
        if (depth > maxNestingDepth) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                boolean isVaultMarker = name.equals(MarkerType.VAULT.folderName());

                if (isVaultMarker && depth > 1) {
                    String holder = readHolderRepoSlugBestEffort(child);
                    throw new VaultException("directory '" + child
                            + "' is already claimed by vault '" + holder + "'");
                }

                if (isReservedMarkerFolderName(name)) continue;

                scanDescendantsForMarker(child, depth + 1);
            }
        } catch (IOException e) {
            throw new VaultException("Unable to scan for nested vault markers under " + dir, e);
        }
    }

    /**
     * True if {@code name} matches the reserved folder name of any
     * {@link MarkerType} — not just {@link MarkerType#VAULT} — so a future
     * {@code WORKSPACE}/{@code CONFIG}/etc. folder is also skipped during
     * descent by {@link #scanDescendantsForMarker}, even though only
     * {@code VAULT} is actively claimed/checked today.
     *
     * @param name a single path component (directory name) to test
     * @return {@code true} if {@code name} is any reserved marker folder name
     */
    private static boolean isReservedMarkerFolderName(String name) {
        return Arrays.stream(MarkerType.values()).anyMatch(t -> t.folderName().equals(name));
    }

    /**
     * Resolves the reserved marker folder itself under {@code dir}, e.g.
     * {@code <vaultDir>/.nomadsync-vault}.
     *
     * @param dir  the directory the marker folder belongs to
     * @param type which kind of marker folder to resolve
     * @return the marker folder's path — not guaranteed to exist
     */
    private static Path markerFolder(Path dir, MarkerType type) {
        return dir.resolve(type.folderName());
    }

    /**
     * Resolves the JSON descriptor file inside a marker folder, e.g.
     * {@code <vaultDir>/.nomadsync-vault/descriptor.json}.
     *
     * @param dir  the directory the marker folder belongs to
     * @param type which kind of marker folder to resolve
     * @return the descriptor file's path — not guaranteed to exist
     */
    private static Path markerDescriptor(Path dir, MarkerType type) {
        return markerFolder(dir, type).resolve(MarkerType.DESCRIPTOR_FILE_NAME);
    }

    /**
     * Best-effort read of the {@code repoSlug} recorded in the descriptor inside
     * {@code markerFolder} — used only to produce a human-readable "claimed by
     * vault 'X'" message in exceptions. Never throws; an unreadable or missing
     * descriptor yields a generic placeholder rather than failing the caller,
     * which is always already in the process of reporting a different error.
     *
     * @param markerFolder the reserved marker folder to read from
     * @return the holder's {@code repoSlug}, or {@code "an unknown vault"} if
     *         the descriptor is missing or unreadable
     */
    private String readHolderRepoSlugBestEffort(Path markerFolder) {
        try {
            VaultMarker existing = JsonMapper.loadVaultMarkerFromFile(
                    markerFolder.resolve(MarkerType.DESCRIPTOR_FILE_NAME).toFile());
            return existing != null ? existing.repoSlug() : "an unknown vault";
        } catch (IOException e) {
            logService.warn("readHolderRepoSlugBestEffort - unable to read descriptor at "
                    + markerFolder + ": " + e.getMessage());
            return "an unknown vault";
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    /**
     * Creates a new vault with a generated UUID, registers it in memory, and persists.
     *
     * <p>Two pre-conditions are checked before registration:</p>
     * <ol>
     *   <li>No existing vault shares the same {@code repoSlug} ({@code <owner>/<name>}).
     *       Two vaults with the same {@code name} but different {@code owner}s are
     *       explicitly allowed.</li>
     *   <li>No existing vault shares the same local {@code path}, nor does the new
     *       path overlap (nest inside or contain) any existing vault's path.</li>
     * </ol>
     *
     * <p>Before persisting, the vault's path is atomically claimed via
     * {@link #claimVaultPath(Vault)} — a {@code .nomadsync-vault} marker folder is
     * created in the directory, first verifying (via {@link #checkNoNestingConflict})
     * that no ancestor or nearby descendant directory is already claimed by an
     * unrelated vault, possibly from a different workspace never loaded in this
     * session. A claim failure aborts the operation before any in-memory or
     * on-disk registration state is touched.</p>
     *
     * @param owner GitHub account that owns the remote repository
     * @param name  remote repository name — combined with {@code owner} to form the
     *              {@code repoSlug}; the combination must be unique among registered vaults
     * @param path  absolute path to the vault directory on the local filesystem;
     *              must be unique among registered vaults and must not overlap or
     *              be claimed by another vault
     * @return the created {@link Vault} with its generated id
     * @throws VaultException if a vault with the same {@code repoSlug} or {@code path}
     *                          already exists, if the path overlaps another vault's
     *                          path, if the path (or a nearby ancestor/descendant)
     *                          is already claimed by another vault, or if persistence fails
     */
    public Vault create(String owner, String name, String path) throws VaultException {
        String repoSlug = owner + "/" + name;
        String absolutePath = Path.of(path).toAbsolutePath().normalize().toString();

        if (findByRepoSlug(repoSlug).isPresent()) {
            throw new VaultException("duplicated repoSlug: " + repoSlug);
        }
        if (findByPath(absolutePath).isPresent()) {
            throw new VaultException("duplicated path: " + absolutePath);
        }
        Optional<Vault> overlapping = findOverlappingPath(absolutePath);
        if (overlapping.isPresent()) {
            throw new VaultException("path '" + absolutePath + "' overlaps with vault '"
                    + overlapping.get().getRepoSlug() + "' at '" + overlapping.get().getPath()
                    + "' - nested vault paths are not allowed");
        }

        Vault vault = new Vault(UUID.randomUUID().toString(), owner, name, absolutePath);
        claimVaultPath(vault);

        vaults.put(vault.getId(), vault);
        save();
        return vault;
    }

    /**
     * Updates an existing vault in memory and persists.
     *
     * <p>The vault is identified by its {@code id} — both the vault and its id
     * must not be {@code null}.</p>
     *
     * <p>Constraints checked before persisting: the resulting {@code repoSlug}
     * and {@code path} must not collide with, or overlap, a <em>different</em>
     * vault's values — no-op renames/path updates (same value as before) are
     * always allowed.</p>
     *
     * <p>If the path actually changes, the new path is atomically claimed via
     * {@link #claimVaultPath(Vault)} (same nesting/collision checks as
     * {@link #create}) <strong>before</strong> the old path's
     * {@code .nomadsync-vault} marker is released via {@link #releaseVaultMarker}.
     * Claim-then-release ordering ensures the old marker is never removed unless
     * the new claim already succeeded. No path change means no claim/release
     * activity at all.</p>
     *
     * @param vault the vault to update
     * @throws IllegalArgumentException if {@code vault} or {@code vault.getId()} is {@code null}
     * @throws VaultException           if the resulting {@code repoSlug} or {@code path}
     *                                   collides or overlaps with a different vault's
     *                                   value, if a changed path cannot be claimed, or if
     *                                   persistence fails
     */
    public void update(Vault vault) throws VaultException {
        ValidationUtil.requireNonNull(vault, "vault");
        ValidationUtil.requireNonNull(vault.getId(), "vault.id");

        vault.setPath(Path.of(vault.getPath()).toAbsolutePath().normalize().toString());

        Optional<Vault> slugConflict = findByRepoSlug(vault.getRepoSlug());
        if (slugConflict.isPresent() && !slugConflict.get().getId().equals(vault.getId())) {
            throw new VaultIntegrityException("duplicated repoSlug: " + vault.getRepoSlug());
        }

        Optional<Vault> pathConflict = findByPath(vault.getPath());
        if (pathConflict.isPresent() && !pathConflict.get().getId().equals(vault.getId())) {
            throw new VaultIntegrityException("duplicated path: " + vault.getPath());
        }

        // Explicit id-exclusion here is belt-and-braces: findOverlappingPath already
        // excludes an exact-path match on string equality, which would normally
        // cover "comparing the vault against itself" too — but being explicit about
        // excluding this vault's own id makes the intent unambiguous if the method
        // is ever refactored.
        Optional<Vault> overlapping = findOverlappingPath(vault.getPath())
                .filter(v -> !v.getId().equals(vault.getId()));
        if (overlapping.isPresent()) {
            throw new VaultIntegrityException("path '" + vault.getPath() + "' overlaps with vault '"
                    + overlapping.get().getRepoSlug() + "' at '" + overlapping.get().getPath()
                    + "' - nested vault paths are not allowed");
        }

        Optional<Vault> currentEntry = findById(vault.getId());
        String oldPath = currentEntry.map(Vault::getPath).orElse(null);
        boolean pathChanged = oldPath != null && !oldPath.equals(vault.getPath());

        if (pathChanged) {
            claimVaultPath(vault);
            releaseVaultMarker(oldPath);
        }

        vaults.put(vault.getId(), vault);
        save();
    }

    /**
     * Removes a vault by UUID from memory and persists.
     *
     * <p>No-op if the id does not exist in memory. The vault's
     * {@code .nomadsync-vault} marker, if any, is released via
     * {@link #releaseVaultMarker} — best-effort, never fails the overall
     * operation. The local directory and its contents are never touched — only
     * the registration and its claim marker are removed.</p>
     *
     * <p>Logging: a single {@code INFO} line announces the operation at the start,
     * identifying the target by {@code repoSlug} when the id is currently registered,
     * or by raw id otherwise (e.g. a no-op call for an unknown id).</p>
     *
     * @param id the UUID of the vault to remove — must not be {@code null} or blank
     * @throws IllegalArgumentException if {@code id} is {@code null} or blank
     * @throws VaultException           if persistence fails
     */
    public void delete(String id) throws VaultException {
        ValidationUtil.requireNonBlank(id, "id");
        findById(id).ifPresent(v -> releaseVaultMarker(v.getPath()));
        String label = Optional.ofNullable(vaults.get(id))
                .map(Vault::getRepoSlug)
                .orElse(id);
        logService.info("delete - " + label + " - removing vault");
        vaults.remove(id);
        save();
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    /**
     * Returns all registered vaults as a defensive copy.
     *
     * <p>Structural modifications (add/remove) to the returned list do not affect
     * the internal map. The {@link Vault} objects themselves are shared references —
     * mutate via {@link #update(Vault)} to persist changes. Pure query — emits no
     * log, per the project's logging conventions.</p>
     *
     * @return a new list containing all registered vaults
     */
    public List<Vault> findAll() {
        return new ArrayList<>(vaults.values());
    }

    /**
     * Returns the vault with the given UUID, or {@link Optional#empty()} if not found.
     *
     * <p>O(1) lookup via the internal {@link HashMap}. Pure query — emits no log,
     * per the project's logging conventions.</p>
     *
     * @param id the UUID to look up
     * @return an {@link Optional} containing the vault, or empty if not registered
     */
    public Optional<Vault> findById(String id) {
        return Optional.ofNullable(vaults.get(id));
    }

    /**
     * Returns the vault whose {@code repoSlug} ({@code <owner>/<name>}) matches exactly,
     * or {@link Optional#empty()} if not found.
     *
     * <p>{@code repoSlug} is the canonical unique identifier — see
     * {@link io.aledep10.nomadsync.service.VaultService} for uniqueness constraints.
     * Pure query — emits no log, per the project's logging conventions.</p>
     *
     * @param repoSlug full identifier in {@code "<owner>/<name>"} form,
     *                 e.g. {@code "Owner/portfolio"}
     * @return an {@link Optional} containing the vault, or empty if not registered
     */
    public Optional<Vault> findByRepoSlug(String repoSlug) {
        return vaults.values().stream()
                .filter(v -> v.getRepoSlug().equals(repoSlug))
                .findFirst();
    }

    /**
     * Returns all vaults whose {@code name} matches, regardless of {@code owner}.
     *
     * <p>{@code name} alone is <strong>not</strong> a unique identifier — two vaults
     * belonging to different owners may share the same {@code name}
     * (e.g. {@code "alice/portfolio"} and {@code "bob/portfolio"}).</p>
     *
     * <p>This method is intended exclusively for CLI vault resolution
     * ({@code --vault=<name>} argument parsing, GRM M7 Step D5). It must
     * <strong>not</strong> be used for uniqueness checks — use
     * {@link #findByRepoSlug(String)} for that purpose. Pure query — emits no
     * log, per the project's logging conventions.</p>
     *
     * <p>The returned list size drives the resolution logic:</p>
     * <ul>
     *   <li>{@code 0} — vault not found</li>
     *   <li>{@code 1} — unambiguous match, regardless of owner</li>
     *   <li>{@code >1} — ambiguous; caller should request {@code <owner>/<name>}</li>
     * </ul>
     *
     * @param name the vault name to look up — matched against {@link Vault#getName()}
     * @return a new list of all vaults with this {@code name}; empty if none match
     */
    public List<Vault> findAllByName(String name) {
        return vaults.values().stream()
                .filter(v -> v.getName().equals(name))
                .collect(Collectors.toList());
    }

    /**
     * Returns the vault whose local {@code path} matches exactly,
     * or {@link Optional#empty()} if not found.
     *
     * <p>Used internally by {@link #create} and {@link #update} to enforce
     * path uniqueness, and by {@link #validateUniquePaths} during {@link #load}.
     * Pure query — emits no log, per the project's logging conventions.</p>
     *
     * @param path the absolute path to look up
     * @return an {@link Optional} containing the vault, or empty if not registered
     */
    private Optional<Vault> findByPath(String path) {
        return vaults.values().stream()
                .filter(v -> v.getPath().equals(path))
                .findFirst();
    }

    /**
     * Returns the first registered vault whose path overlaps with the given path —
     * either one contains the other (in either direction). Overlap is dangerous
     * regardless of direction: a Git operation (e.g. {@code git add .}) on the
     * outer vault would silently absorb files belonging to the inner one.
     *
     * <p>Unlike {@link #findByPath}, which performs an exact-match lookup relied
     * upon by {@link #delete}, {@link #update}'s no-op checks, and other callers
     * that need a precise identity match, this method is used exclusively as an
     * additional structural safety check in {@link #create} and {@link #update} —
     * it must never replace {@code findByPath}'s exact-match contract.</p>
     *
     * <p>Exact-path matches are deliberately excluded here — that case is already
     * reported separately (and with a clearer message: "duplicated path") by the
     * existing {@code findByPath} check that runs immediately before this one.</p>
     *
     * @param path the absolute, normalized path to check for overlap
     * @return an {@link Optional} containing the first overlapping vault found
     *         (excluding an exact match), or empty if none overlap
     */
    private Optional<Vault> findOverlappingPath(String path) {
        Path candidate = Path.of(path);
        return vaults.values().stream()
                .filter(v -> !v.getPath().equals(path))
                .filter(v -> {
                    Path existing = Path.of(v.getPath());
                    return candidate.startsWith(existing) || existing.startsWith(candidate);
                })
                .findFirst();
    }

    // ── Snapshot (FIFO) ───────────────────────────────────────────────────────

    /**
     * Creates a FIFO backup snapshot of the vault directory.
     *
     * <h2>Sequence</h2>
     * <ol>
     *   <li>Create {@link #backupsRoot} if it does not exist.</li>
     *   <li>Read active ignore patterns via {@link GitignoreService#forSnapshot(Path)}.</li>
     *   <li>List existing snapshots for this vault — if {@code >= 3}, delete the oldest.</li>
     *   <li>Atomically reserve a unique snapshot directory name (see below), then walk
     *       the vault tree: skip directories whose relative path matches an ignore
     *       pattern; copy files whose relative path matches no ignore pattern.</li>
     * </ol>
     *
     * <h2>Snapshot directory naming and uniqueness</h2>
     * <p>The base name is {@code <repoSlug with "/" replaced by "_">_<minute timestamp>}
     * — minute precision is intentional for readability in the common case (a single
     * user-triggered sync/relocate has no reason to repeat within the same minute).
     * Under rapid repeated or concurrent calls (e.g. stress testing, or a future
     * daemon/CLI race), the base name may already be taken; the directory name is
     * therefore reserved via {@link Files#createDirectory}, which atomically fails
     * if a directory with that exact name already exists. On collision, an
     * incrementing, zero-padded numeric suffix is appended and creation is retried,
     * until an unused name is found. This is safe across separate OS processes, not
     * just threads within this JVM, because directory creation atomicity is
     * enforced by the filesystem itself — and as a side effect, a minute with
     * multiple suffixed snapshots is a visible, human-readable signal that several
     * operations were attempted in rapid succession.</p>
     *
     * <p>Snapshots are stored under {@link #backupsRoot} (configured via
     * {@code path.backups} or resolved relative to {@code configDir} otherwise).</p>
     *
     * <p>Files and directories matching active gitignore patterns are excluded from
     * the snapshot — negated patterns ({@code !}) are included.</p>
     *
     * <p>Logging: a single {@code INFO} line announces the operation at the start.</p>
     *
     * @param vault the vault to snapshot
     * @throws VaultException     if the snapshot cannot be created, including if no
     *                             unique directory name could be reserved after
     *                             repeated attempts (should not normally occur)
     * @throws GitignoreException if the {@code .gitignore} file cannot be read
     */
    public void makeVaultSnapshot(Vault vault)
            throws VaultException, GitignoreException {
        Path vaultDir = Path.of(vault.getPath());
        String vaultName = vault.getRepoSlug().replace("/", "_");
        logService.info("makeVaultSnapshot - " + vaultName + " - creating snapshot");

        try {
            Files.createDirectories(backupsRoot);
            List<PathMatcher> matchers = gitignoreService.forSnapshot(vaultDir);

            List<Path> snapshots;
            try (Stream<Path> stream = Files.list(backupsRoot)) {
                snapshots = stream
                        .filter(p -> p.getFileName().toString().startsWith(vaultName + "_"))
                        .sorted()
                        .toList();
            }
            if (snapshots.size() >= 3) {
                FileUtil.deleteRecursively(snapshots.getFirst());
            }

            String timestamp = DateFormats.nowSnapshot();
            String baseName  = vaultName + "_" + timestamp;
            Path snapshotDir = reserveSnapshotDirectory(baseName);

            Files.walkFileTree(vaultDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    Path relative = vaultDir.relativize(dir);
                    if (matchers.stream().anyMatch(m -> m.matches(relative))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(snapshotDir.resolve(relative));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Path relative = vaultDir.relativize(file);
                    if (matchers.stream().noneMatch(m -> m.matches(relative))) {
                        Files.copy(file, snapshotDir.resolve(relative));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

        } catch (IOException e) {
            throw new VaultException("Could not create snapshot for " + vaultName + ": " + e.getMessage(), e);
        }
    }

    /**
     * Atomically reserves a unique directory under {@link #backupsRoot} whose name
     * starts with {@code baseName}, creating it in the process.
     *
     * <p>Tries {@code baseName} first; on collision (directory already exists —
     * detected via {@link Files#createDirectory}, which is atomic at the filesystem
     * level and therefore safe across concurrent processes, not just threads),
     * retries with an incrementing, zero-padded suffix ({@code baseName-02},
     * {@code baseName-03}, ...) until an unused name is successfully created.</p>
     *
     * @param baseName the preferred directory name, without any disambiguating suffix
     * @return the path of the newly created, guaranteed-unique directory
     * @throws IOException if directory creation fails for a reason other than the
     *                       name already being taken, or if no unique name could be
     *                       found within a bounded number of attempts
     */
    private Path reserveSnapshotDirectory(String baseName) throws IOException {
        Path candidate = backupsRoot.resolve(baseName);
        try {
            Files.createDirectory(candidate);
            return candidate;
        } catch (FileAlreadyExistsException e) {
            for (int attempt = 2; attempt <= 100; attempt++) {
                candidate = backupsRoot.resolve(baseName + "-" + String.format("%02d", attempt));
                try {
                    Files.createDirectory(candidate);
                    return candidate;
                } catch (FileAlreadyExistsException retryCollision) {
                    // try next suffix
                }
            }
            throw new IOException("Unable to reserve a unique snapshot directory for " + baseName
                    + " after 100 attempts");
        }
    }

    // ── Conflicts ─────────────────────────────────────────────────────────────

    /**
     * Moves a conflict file from a temporary location into the conflicts root.
     *
     * <p>The destination path is:
     * {@code conflictsRoot/<conflictDirName>/<filename>}</p>
     *
     * <h2>Flow</h2>
     * <p>The caller ({@link io.aledep10.nomadsync.service.GitService}) writes the
     * remote version of a conflicted file to a temp path via
     * {@link io.aledep10.nomadsync.util.CommandUtil#runCommandToFile}, then delegates
     * the move here. {@link Files#move} with {@link StandardCopyOption#REPLACE_EXISTING}
     * is atomic on same-filesystem moves — no partial writes are visible to other
     * threads during the operation.</p>
     *
     * <p>After a successful move, the source temp file no longer exists. The caller
     * wraps the call in a {@code try/finally} with {@link Files#deleteIfExists} on
     * {@code sourcePath} as a safety net in case this method throws before completing
     * the move.</p>
     *
     * <p>Logging: a single {@code INFO} line announces the operation at the start.</p>
     *
     * @param conflictDirName directory name for this conflict session,
     *                        typically {@code <vaultName>_<timestamp>} — shared
     *                        across all files belonging to the same synchronisation run
     * @param filename        basename of the conflicted file (no path separators)
     * @param sourcePath      temp file containing the remote version of the conflict
     * @throws VaultException if the conflict directory cannot be created or the move fails
     */
    public void saveConflict(String conflictDirName, String filename, Path sourcePath)
            throws VaultException {
        logService.info("saveConflict - " + conflictDirName + "/" + filename
                + " - saving conflict file");
        try {
            Path conflictDir = conflictsRoot.resolve(conflictDirName);
            Files.createDirectories(conflictDir);
            Files.move(sourcePath, conflictDir.resolve(filename),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new VaultException("Could not save conflict: " + filename, e);
        }
    }
}