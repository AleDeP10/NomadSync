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
 * {@code backup.path} and {@code conflicts.path} in the application properties.
 * If those keys are absent, both directories fall back to the application working
 * directory ({@code user.dir}), keeping the layout self-contained without
 * requiring explicit configuration in simple deployments.</p>
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
     * Primary constructor — backup and conflict paths loaded from properties.
     *
     * <p>If {@code backup.path} or {@code conflicts.path} are absent from the
     * provided properties, both fall back to subdirectories of the application
     * working directory ({@code user.dir}).</p>
     *
     * @param properties       application properties containing {@code vaults.file}
     *                         and optionally {@code backup.path}, {@code conflicts.path}
     * @param gitignoreService used to read active ignore patterns during snapshot creation
     * @param logService       shared logging service
     */
    public VaultService(Properties properties,
                        GitignoreService gitignoreService,
                        LogService logService) {
        this.vaultFile        = new File(properties.getProperty("vaults.file"));
        this.gitignoreService = gitignoreService;
        this.logService       = logService;
        this.backupsRoot      = Path.of(properties.getProperty(
                "backup.path", FALLBACK_ROOT + "/backups"));
        this.conflictsRoot    = Path.of(properties.getProperty(
                "conflicts.path", FALLBACK_ROOT + "/remote-conflicts"));
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Loads all vaults from {@code vaults.json} and replaces the current in-memory state.
     *
     * <p>If the file does not exist, the in-memory state is cleared and an empty list
     * is returned — no exception is thrown.</p>
     *
     * @return the list of loaded vaults
     * @throws IOException if the file exists but cannot be read or parsed
     */
    public List<Vault> load() throws IOException {
        List<Vault> loaded = JsonMapper.loadVaultsFromFile(vaultFile);
        vaults.clear();
        loaded.forEach(v -> vaults.put(v.getId(), v));
        return new ArrayList<>(vaults.values());
    }

    /**
     * Persists the current in-memory vault state to {@code vaults.json}.
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
     * @param name human-readable vault name
     * @param path absolute path to the vault directory
     * @return the created {@link Vault} with its generated id
     * @throws IOException if persistence fails
     */
    public Vault create(String name, String path) throws IOException {
        Vault vault = new Vault(UUID.randomUUID().toString(), name, path);
        vaults.put(vault.getId(), vault);
        save();
        return vault;
    }

    /**
     * Updates an existing vault in memory and persists.
     *
     * @param vault the vault to update — must not be {@code null},
     *              and {@code vault.getId()} must not be {@code null}
     * @throws IllegalArgumentException if {@code vault} or its id is {@code null}
     * @throws IOException              if persistence fails
     */
    public void update(Vault vault) throws IOException {
        ValidationUtil.requireNonNull(vault, "vault");
        ValidationUtil.requireNonNull(vault.getId(), "vault.id");
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
     * @return a new list containing all registered vaults
     */
    public List<Vault> findAll() {
        return new ArrayList<>(vaults.values());
    }

    /**
     * Returns the vault with the given UUID, or {@link Optional#empty()} if not found.
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
     * <p>Snapshots are stored under {@link #backupsRoot} (configured via
     * {@code backup.path} or falling back to {@code user.dir/backups}).
     * The snapshot directory is named {@code <vaultName>_<timestamp>}.</p>
     *
     * <p>At most 3 snapshots are kept — the oldest is deleted before creating a new one
     * when the count reaches 3.</p>
     *
     * <p>Files and directories matching active gitignore patterns are excluded.</p>
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

    // ── Private helpers ───────────────────────────────────────────────────────

    private void deleteRecursively(Path root) throws IOException {
        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }
}