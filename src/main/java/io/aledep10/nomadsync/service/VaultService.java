package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.exception.VaultException;
import io.aledep10.nomadsync.gitignore.exception.GitignoreException;
import io.aledep10.nomadsync.orchestrator.Vault;
import io.aledep10.nomadsync.util.DateFormats;
import io.aledep10.nomadsync.util.JsonMapper;
import io.aledep10.nomadsync.util.ValidationUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/**
 * Manages the lifecycle of registered {@link Vault} instances — load, persist, and CRUD.
 *
 * <p>Vaults are stored in memory as a {@link HashMap} keyed by UUID for O(1) lookup.
 * Every mutation (create, update, delete) is immediately persisted to {@code vaults.json}
 * via {@link JsonMapper} to guarantee consistency between memory and disk.</p>
 *
 * <h2>Snapshot paths</h2>
 * <p>Backup and conflict directories can be configured explicitly via
 * {@code path.backup} and {@code path.conflicts} in the application properties.
 * If those keys are absent, both directories fall back to subdirectories of the
 * application working directory ({@code user.dir}), keeping the layout
 * self-contained without requiring explicit configuration in simple deployments.</p>
 *
 * <h2>Vault name uniqueness</h2>
 * <p>Vault {@code name} must be unique across all registered vaults — it determines
 * the {@code backups/<name>_<timestamp>/} and {@code remote-conflicts/<name>_<timestamp>/}
 * directory names. Two vaults sharing a {@code name} (even under different {@code owner}s)
 * would silently collide on the same snapshot/conflict directories. {@link #load()},
 * {@link #create(String, String, String)} and {@link #update(Vault)} all enforce this
 * constraint by throwing {@link VaultException}.</p>
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
 */
public class VaultService {

    private static final String FALLBACK_ROOT = System.getProperty("user.dir");

    /** Package-private for test assertions on file existence. */
    final File vaultFile;

    private final Map<String, Vault> vaults = new HashMap<>();
    private final GitignoreService gitignoreService;
    private final LogService logService;
    private final Path backupsRoot;
    private final Path conflictsRoot;

    /**
     * Constructs the service. Does not load from disk — call {@link #load()} explicitly.
     *
     * <p>If {@code path.backup} or {@code path.conflicts} are absent from the
     * provided properties, both fall back to subdirectories of the application
     * working directory ({@code user.dir}).</p>
     *
     * @param properties       application properties containing {@code path.vaults}
     *                         and optionally {@code path.backup}, {@code path.conflicts}
     * @param gitignoreService used to read active ignore patterns during snapshot creation
     * @param logService       shared logging service
     */
    public VaultService(Properties properties,
                        GitignoreService gitignoreService,
                        LogService logService) {
        this.vaultFile        = new File(properties.getProperty("path.vaults"));
        this.gitignoreService = gitignoreService;
        this.logService       = logService;
        this.backupsRoot      = Path.of(properties.getProperty(
                "path.backup", FALLBACK_ROOT + "/backups"));
        this.conflictsRoot    = Path.of(properties.getProperty(
                "path.conflicts", FALLBACK_ROOT + "/remote-conflicts"));
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Loads all vaults from {@code vaults.json} and replaces the current in-memory state.
     *
     * <p>If the file does not exist, the in-memory state is cleared and an empty list
     * is returned — no exception is thrown.</p>
     *
     * @return the list of loaded vaults
     * @throws IOException    if the file exists but cannot be read or parsed
     * @throws VaultException if two or more vaults share the same {@code name}
     */
    public List<Vault> load() throws IOException, VaultException {
        List<Vault> loaded = JsonMapper.loadVaultsFromFile(vaultFile);
        validateUniqueNames(loaded);
        vaults.clear();
        loaded.forEach(v -> vaults.put(v.getId(), v));
        return new ArrayList<>(vaults.values());
    }

    /**
     * Persists the current in-memory vault state to {@code vaults.json}.
     *
     * <p>Called automatically by all mutating operations. Can be called explicitly
     * if external mutations to {@link Vault} objects need to be flushed to disk.</p>
     *
     * @throws IOException if the file cannot be written
     */
    public void save() throws IOException {
        JsonMapper.saveVaultsToFile(vaultFile, new ArrayList<>(vaults.values()));
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    /**
     * Creates a new vault with a generated UUID, registers it in memory, and persists.
     *
     * @param owner GitHub account that owns the remote repository
     * @param name  human-readable vault name, also the remote repository name —
     *              must be unique across all registered vaults
     * @param path  absolute path to the vault directory on the local filesystem
     * @return the created {@link Vault} with its generated id
     * @throws VaultException if a vault with the same {@code name} already exists
     * @throws IOException    if persistence fails
     */
    public Vault create(String owner, String name, String path) throws IOException, VaultException {
        if (findByName(name).isPresent()) {
            throw new VaultException("duplicated vault name: " + name);
        }
        Vault vault = new Vault(UUID.randomUUID().toString(), owner, name, path);
        vaults.put(vault.getId(), vault);
        save();
        return vault;
    }

    /**
     * Updates an existing vault in memory and persists.
     *
     * <p>The vault is identified by its {@code id} — both the vault and its id
     * must not be {@code null}. If {@code vault.getName()} is being changed to a
     * name already used by a <em>different</em> vault, the update is rejected.</p>
     *
     * @param vault the vault to update
     * @throws IllegalArgumentException if {@code vault} or {@code vault.getId()} is {@code null}
     * @throws VaultException           if {@code vault.getName()} collides with a different vault's name
     * @throws IOException              if persistence fails
     */
    public void update(Vault vault) throws IOException, VaultException {
        ValidationUtil.requireNonNull(vault, "vault");
        ValidationUtil.requireNonNull(vault.getId(), "vault.id");

        Optional<Vault> existing = findByName(vault.getName());
        if (existing.isPresent() && !existing.get().getId().equals(vault.getId())) {
            throw new VaultException("duplicated vault name: " + vault.getName());
        }

        vaults.put(vault.getId(), vault);
        save();
    }

    /**
     * Removes a vault by UUID from memory and persists.
     *
     * <p>No-op if the id does not exist in memory.</p>
     *
     * @param id the UUID of the vault to remove — must not be {@code null} or blank
     * @throws IllegalArgumentException if {@code id} is {@code null} or blank
     * @throws IOException              if persistence fails
     */
    public void delete(String id) throws IOException {
        ValidationUtil.requireNonBlank(id, "id");
        vaults.remove(id);
        save();
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    /**
     * Returns all registered vaults as a defensive copy.
     *
     * <p>Structural modifications (add/remove) to the returned list do not affect
     * the internal map. The {@link Vault} objects themselves are shared references —
     * mutate via {@link #update(Vault)} to persist changes.</p>
     *
     * @return a new list containing all registered vaults
     */
    public List<Vault> findAll() {
        return new ArrayList<>(vaults.values());
    }

    /**
     * Returns the vault with the given UUID, or {@link Optional#empty()} if not found.
     *
     * <p>O(1) lookup via the internal {@link HashMap}.</p>
     *
     * @param id the UUID to look up
     * @return an {@link Optional} containing the vault, or empty if not registered
     */
    public Optional<Vault> findById(String id) {
        return Optional.ofNullable(vaults.get(id));
    }

    /**
     * Returns the first vault with the given name, or {@link Optional#empty()} if not found.
     *
     * @param name the vault name to look up
     * @return an {@link Optional} containing the vault, or empty if not registered
     */
    public Optional<Vault> findByName(String name) {
        return vaults.values().stream()
                .filter(v -> v.getName().equals(name))
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
     *   <li>Walk the vault tree: skip directories whose relative path matches an ignore
     *       pattern; copy files whose relative path matches no ignore pattern.</li>
     * </ol>
     *
     * <p>Snapshots are stored under {@link #backupsRoot} (configured via
     * {@code path.backup} or falling back to {@code user.dir/backups}).
     * Each snapshot directory is named {@code <vaultName>_<timestamp>}.</p>
     *
     * <p>Files and directories matching active gitignore patterns are excluded from
     * the snapshot — negated patterns ({@code !}) are included.</p>
     *
     * @param vaultPath absolute path to the vault directory
     * @throws VaultException     if the snapshot cannot be created
     * @throws GitignoreException if the {@code .gitignore} file cannot be read
     */
    public void makeVaultSnapshot(String vaultPath)
            throws VaultException, GitignoreException {
        Path   vaultDir  = Path.of(vaultPath);
        String vaultName = vaultDir.getFileName().toString();

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
                deleteRecursively(snapshots.getFirst());
            }

            String timestamp   = DateFormats.nowSnapshot();
            Path   snapshotDir = backupsRoot.resolve(vaultName + "_" + timestamp);

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
            throw new VaultException("Could not create snapshot for " + vaultName, e);
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
     * @param conflictDirName directory name for this conflict session,
     *                        typically {@code <vaultName>_<timestamp>} — shared
     *                        across all files belonging to the same synchronisation run
     * @param filename        basename of the conflicted file (no path separators)
     * @param sourcePath      temp file containing the remote version of the conflict
     * @throws VaultException if the conflict directory cannot be created or the move fails
     */
    public void saveConflict(String conflictDirName, String filename, Path sourcePath)
            throws VaultException {
        try {
            Path conflictDir = conflictsRoot.resolve(conflictDirName);
            Files.createDirectories(conflictDir);
            Files.move(sourcePath, conflictDir.resolve(filename),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new VaultException("Could not save conflict: " + filename, e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Recursively deletes a directory and all its contents.
     *
     * <p>Files are deleted before their parent directories by sorting in reverse
     * order — the OS refuses to delete a non-empty directory.</p>
     *
     * @param root the directory to delete
     * @throws IOException if any file or directory cannot be deleted
     */
    private void deleteRecursively(Path root) throws IOException {
        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    /**
     * Validates that no two vaults in the given list share the same {@code name}.
     *
     * <p>Vault names are used to derive {@code backups/<name>_<timestamp>/} and
     * {@code remote-conflicts/<name>_<timestamp>/} directory names. Two vaults with
     * the same {@code name} — even under different {@code owner}s — would collide
     * on the same snapshot/conflict directories, silently mixing their contents.</p>
     *
     * @param loaded vaults freshly deserialised from {@code vaults.json}
     * @throws VaultException if any name appears more than once
     */
    private void validateUniqueNames(List<Vault> loaded) throws VaultException {
        Set<String> seen = new HashSet<>();
        for (Vault vault : loaded) {
            if (!seen.add(vault.getName())) {
                throw new VaultException("duplicated vault name in vaults.json: " + vault.getName());
            }
        }
    }
}