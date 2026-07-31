package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.config.NomadProperties;
import io.aledep10.nomadsync.exception.*;
import io.aledep10.nomadsync.gitignore.exception.GitignoreException;
import io.aledep10.nomadsync.marker.MarkerType;
import io.aledep10.nomadsync.marker.VaultMarker;
import io.aledep10.nomadsync.vault.Vault;
import io.aledep10.nomadsync.util.*;

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
 * <h2>Marker-based path protection — delegated to {@link MarkerService}</h2>
 * <p>Beyond the in-memory uniqueness checks above (which only see vaults already
 * loaded in the current session), every vault's directory is marked on disk with a
 * reserved {@code .nomadsync-vault/} folder. All of the generic mechanics — the
 * atomic claim, the cross-type ancestor scan, the {@code VAULT}-only descendant
 * scan, release, and confirm-on-load — live in {@link MarkerService}, shared across
 * every {@link io.aledep10.nomadsync.marker.MarkerType}. This class's own
 * responsibility is narrower: build a {@link VaultMarker} with vault-specific
 * identity ({@code repoSlug}, {@code workspacePath}) and hand it to
 * {@code markerService}, translating any {@link MarkerClaimException} into this
 * class's own {@link VaultException} contract so callers of {@code create}/
 * {@code update}/{@code delete}/{@code load} see no change in the exceptions
 * they've always caught.</p>
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

     /**
     * Resolves the {@code --vault} flag to a {@link Vault} instance.
     *
     * <ul>
     *   <li>{@code null} flag → returns {@code null} (broadcast or mandatory error handled by caller)</li>
     *   <li>{@code owner/name} → exact {@link Vault#getRepoSlug()} match, otherwise {@link VaultNotFoundException}</li>
     *   <li>{@code name} → resolves if exactly one vault has that name;
     *       {@link VaultNotFoundException} if zero match, {@link VaultAmbiguousException} if multiple</li>
     * </ul>
     *
     * <p>Unlike the {@code null}-flag case, a non-null unresolvable flag is always
     * a fatal error for the caller — a vault name typed by the user and not found
     * must never be silently downgraded to a broadcast on all vaults.</p>
     *
     * @param vaultFlag  the raw {@code --vault} value, or {@code null} if absent
     * @return the matching {@link Vault}, or {@code null} if {@code vaultFlag} is {@code null}
     * @throws VaultNotFoundException  if {@code vaultFlag} is non-null and matches no vault
     * @throws VaultAmbiguousException if {@code vaultFlag} is a bare name matching multiple vaults
     */
    public Vault resolveVaultFlag(String vaultFlag)
            throws VaultNotFoundException, VaultAmbiguousException {
        if (vaultFlag == null) return null;

        if (vaultFlag.contains("/")) {
            return vaults.values().stream()
                    .filter(v -> v.getRepoSlug().equals(vaultFlag))
                    .findFirst()
                    .orElseThrow(() -> new VaultNotFoundException(vaultFlag));
        }

        List<Vault> matches = vaults.values().stream()
                .filter(v -> v.getName().equals(vaultFlag))
                .toList();

        if (matches.isEmpty()) {
            throw new VaultNotFoundException(vaultFlag);
        }
        if (matches.size() > 1) {
            throw new VaultAmbiguousException(vaultFlag, matches);
        }
        return matches.getFirst();
    }

    /**
     * Logs all registered vault repoSlugs at ERROR level — used in resolution
     * error messages to help the user identify the correct {@code --vault} value.
     */
    public void listRegistered() {
        logService.error("Registered: "
                + vaults.values().stream()
                .map(Vault::getRepoSlug)
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)"));
    }

    /**
     * Applies {@code --git.*} flags to the mutable credential and configuration
     * fields of the given {@link Vault}.
     *
     * <p>Unknown keys are logged as warnings and silently ignored — they do not
     * cause the command to fail.</p>
     *
     * @param gitFlags   map of {@code git.*} flag keys to their values
     * @param vault      the vault to mutate
     */
    public void applyGitFlagsToVault(Map<String, String> gitFlags, Vault vault) {
        gitFlags.forEach((key, value) -> {
            switch (key) {
                case NomadProperties.Git.NAME     -> vault.setGitName(value);
                case NomadProperties.Git.EMAIL    -> vault.setGitEmail(value);
                case NomadProperties.Git.USERNAME -> vault.setGitUsername(value);
                case NomadProperties.Git.TOKEN    -> vault.setGitToken(value);
                case NomadProperties.Git.BRANCH   -> vault.setGitBranch(value);
                case NomadProperties.Git.REMOTE   -> vault.setGitRemote(value);
                default -> logService.warn("applyGitFlagsToVault: unknown flag '"
                        + key + "' - ignored");
            }
        });
    }

    public static final String CATALOG_FILE_NAME = "catalog.json";
    public static final String BACKUPS_FOLDER_NAME = "backups";
    public static final String CONFLICTS_FOLDER_NAME = "remote-conflicts";

    /** Package-private for test assertions on file existence. */
    final File catalogFile;

    private final Map<String, Vault> vaults = new HashMap<>();
    private final MarkerService markerService;
    private final GitignoreService gitignoreService;
    private final LogService logService;
    private final Path backupsRoot;
    private final Path conflictsRoot;

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
     * @param configDir     directory containing the {@code config.properties}
     *                      file in use — base for resolving all three path
     *                      properties above when relative or absent
     * @param markerService     shared marker protection engine — handles all
     *                          {@code .nomadsync-vault} claim/release/refresh/scan
     *                          mechanics generically; see the class-level Javadoc
     * @param gitignoreService  used to read active ignore patterns during snapshot creation
     * @param logService        shared logging service
     */
    public VaultService(Path configDir,
                        MarkerService markerService,
                        GitignoreService gitignoreService,
                        LogService logService) {
        this.markerService     = markerService;
        this.gitignoreService  = gitignoreService;
        this.logService        = logService;
        this.catalogFile       = configDir.resolve(CATALOG_FILE_NAME).toFile();
        this.backupsRoot       = configDir.resolve(BACKUPS_FOLDER_NAME);
        this.conflictsRoot     = configDir.resolve(CONFLICTS_FOLDER_NAME);
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
     * {@code markerService.refresh(MarkerType.VAULT, ...)} — best-effort, never
     * throws (see {@link MarkerService#refresh} for the full contract).</p>
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

        String now = DateFormats.nowLog();
        for (Vault v : vaults.values()) {
            VaultMarker marker = VaultMarker.create(v.getId(), v.getRepoSlug(), catalogFile.getPath(), now);
            markerService.refresh(MarkerType.VAULT, v.getPath(), marker);
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
     * {@code markerService.claim(MarkerType.VAULT, ...)} — see {@link MarkerService}
     * for the full claim/nesting-conflict contract. A claim failure
     * ({@link MarkerClaimException}, translated here to {@link VaultException})
     * aborts the operation before any in-memory or on-disk registration state
     * is touched.</p>
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
     *                          is already claimed by another marker, or if persistence fails
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
        claimVaultMarker(vault);

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
     * {@code markerService.claim(...)} (same nesting/collision checks as
     * {@link #create}) <strong>before</strong> the old path's marker is released
     * via {@code markerService.release(...)}. Claim-then-release ordering ensures
     * the old marker is never removed unless the new claim already succeeded. No
     * path change means no claim/release activity at all.</p>
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
            claimVaultMarker(vault);
            markerService.release(MarkerType.VAULT, oldPath);
        }

        vaults.put(vault.getId(), vault);
        save();
    }

    /**
     * Removes a vault by UUID from memory and persists.
     *
     * <p>No-op if the id does not exist in memory. The vault's marker, if any, is
     * released via {@code markerService.release(...)} — best-effort, never fails
     * the overall operation. The local directory and its contents are never
     * touched — only the registration and its claim marker are removed.</p>
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
        findById(id).ifPresent(v -> markerService.release(MarkerType.VAULT, v.getPath()));
        String label = Optional.ofNullable(vaults.get(id))
                .map(Vault::getRepoSlug)
                .orElse(id);
        logService.info("delete - " + label + " - removing vault");
        vaults.remove(id);
        save();
    }

    /**
     * Builds a fresh {@link VaultMarker} for {@code vault} and claims its path via
     * {@code markerService}, translating any {@link MarkerClaimException} into this
     * class's own {@link VaultException} contract.
     *
     * @throws VaultException if the path (or a nearby ancestor/descendant) is
     *                          already claimed by another marker
     */
    private void claimVaultMarker(Vault vault) throws VaultException {
        VaultMarker marker = VaultMarker.create(vault.getId(), vault.getRepoSlug(),
                catalogFile.getPath(), DateFormats.nowLog());
        try {
            markerService.claim(MarkerType.VAULT, vault.getPath(), marker);
        } catch (MarkerClaimException e) {
            throw new VaultException(e.getMessage(), e);
        }
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
        return vaults.values().stream().map(Vault::copy).collect(Collectors.toList());
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
        return Optional.ofNullable(vaults.get(id)).map(Vault::copy);
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
                .findFirst().map(Vault::copy);
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
                .map(Vault::copy)
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
