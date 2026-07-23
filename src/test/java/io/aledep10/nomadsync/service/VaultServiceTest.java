package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.exception.VaultException;
import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.marker.MarkerType;
import io.aledep10.nomadsync.marker.VaultMarker;
import io.aledep10.nomadsync.marker.VaultMarkerStrategy;
import io.aledep10.nomadsync.vault.Vault;
import io.aledep10.nomadsync.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/**
 * Unit tests for {@link VaultService}.
 *
 * <h2>Architecture</h2>
 * <p>Every logical group of behaviour lives in its own {@code @Nested} class —
 * {@link LoadTests}, {@link MarkerRefreshTests}, {@link CreateTests},
 * {@link UpdateTests}, {@link DeleteTests}, the {@code find*} groups, and
 * {@link MakeVaultSnapshotTests}. No flat, ungrouped test methods remain at
 * the top level.</p>
 *
 * <h2>Marker responsibility moved to {@code MarkerServiceTest}</h2>
 * <p>Nesting-conflict detection and atomic claim mechanics
 * ({@code checkNoNestingConflict}/{@code claim}/{@code release}/{@code refresh})
 * are now {@link MarkerService}'s responsibility, tested exhaustively in
 * {@code MarkerServiceTest} — this file no longer has {@code CheckNoNestingConflictTests}
 * or {@code ClaimVaultPathTests} nested classes. What remains here is narrower:
 * verifying that {@link VaultService} builds the right {@link VaultMarker} and
 * calls {@code markerService} correctly at the right points in {@code create}/
 * {@code update}/{@code delete}/{@code load} — a real {@link MarkerService}
 * (with a real {@link VaultMarkerStrategy} registered) is used throughout,
 * not a mock, so these tests still exercise the full round-trip down to disk.</p>
 *
 * <h2>Two vaults, two purposes</h2>
 * <p>{@code sharedVault} (static, created once in {@code @BeforeAll}) exists
 * only to give {@link #logService} a place to write its log file — safe to
 * share across every test since no assertion depends on that file's content
 * being empty. It is cleaned up once in {@code @AfterAll}, only if every test
 * in this class passed (see {@link ClassFailureTracker}).</p>
 *
 * <p>{@code testVault} (instance field, obtained fresh per test via the
 * injected {@link TempDirs#newVault}) provides {@code configDir}/
 * {@code catalogFile}/{@code backupsRoot}/{@code conflictsRoot} for
 * {@link #vaultService} — freshly isolated on every single test, since
 * {@code catalogFile}'s path is derived from {@link TestVault#timestamp()},
 * fixed once per {@link TestVault} instance.</p>
 *
 * <h2>Real directories are mandatory for {@code create()}/{@code update()}</h2>
 * <p>{@code MarkerService.claim} atomically reserves the {@code .nomadsync-vault}
 * marker folder via {@code Files.createDirectory} — this requires the target directory
 * to already exist on disk. Use {@link #newVaultDir} for this — it creates a temp
 * directory via the injected {@link TempDirs} and registers it for conditional
 * cleanup.</p>
 *
 * <p>A handful of tests deliberately exercise CWD-relative path normalization by
 * passing a <em>relative</em> string straight to {@code create()}/{@code update()}.
 * These use {@link #newRelativeVaultDir}, which creates the directory at the resolved
 * location (relative to the JVM's working directory) and registers it via
 * {@link TempDirs#registerDir} the same way.</p>
 *
 * <p>Tests in {@link LoadTests} that seed {@code catalog.json} directly via
 * {@link JsonMapper#saveVaultsToFile} (bypassing {@code create()} entirely) do
 * <strong>not</strong> need a real directory — {@code load()}'s marker refresh is
 * best-effort and degrades to a logged warning, never a thrown exception, when a
 * vault's path does not exist on disk.</p>
 *
 * <h2>Identity constraints</h2>
 * <p>The unique identifier is {@code repoSlug} ({@code <owner>/<name>}), not
 * {@code name} alone. Two vaults with the same {@code name} but different
 * {@code owner}s are explicitly permitted. No two vaults may share the same local
 * {@code path} — compared after normalization to an absolute path (see
 * {@link TestUtil#absolute(String)}).</p>
 *
 * <h2>Test naming conventions</h2>
 * <p>Every test method that creates vaults uses names prefixed with the method's
 * own identifier to prevent cross-test collisions when tests run in random order
 * (surefire {@code -DrunOrder=random}). Never mutate the in-memory object returned
 * by {@code create()} directly — construct a copy via {@code new Vault(...)} for
 * rename/update scenarios to avoid corrupting the service's internal map.</p>
 */
@ExtendWith({TempDirCleanupExtension.class, ClassFailureTracker.class})
class VaultServiceTest {

    static TestVault sharedVault;
    static LogService logService;

    TestVault testVault;
    GitignoreService gitignoreService;
    MarkerService markerService;
    VaultService vaultService;

    @BeforeAll
    static void prepareSharedState() throws IOException {
        sharedVault = TestUtil.getTestVault("VaultServiceTest-shared");
        logService  = new LogService(TestUtil.forLogService(sharedVault, LogLevel.DEBUG), sharedVault.rootPath());
    }

    @AfterAll
    static void tearDownAll(ExtensionContext context) throws IOException {
        logService.close();
        if (!ClassFailureTracker.anyTestFailed(context)) {
            TestUtil.cleanup(sharedVault);
        }
    }

    @BeforeEach
    void setUp(TempDirs tempDirs) throws IOException, VaultException {
        testVault = tempDirs.newVault("VaultServiceTest");
        gitignoreService = new GitignoreService(logService);
        Properties properties = TestUtil.forVaultService(testVault);
        markerService = new MarkerService(properties, logService);
        vaultService = new VaultService(properties, testVault.vaultPath(), markerService, gitignoreService, logService);
    }

    // No @AfterEach — testVault and every ad-hoc directory created via
    // newVaultDir/newRelativeVaultDir are registered with the injected
    // TempDirs and cleaned up together, conditionally, by TempDirCleanupExtension.

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Path newVaultDir(TempDirs tempDirs, String prefix) throws IOException {
        return tempDirs.newDir("VaultServiceTest", prefix);
    }

    private Path newRelativeVaultDir(TempDirs tempDirs, String relative) throws IOException {
        Path resolved = Path.of(TestUtil.absolute(relative));
        Path topLevelCreated = topmostMissingAncestor(resolved);
        Files.createDirectories(resolved);
        return tempDirs.registerDir(topLevelCreated);
    }

    /**
     * Walks up from {@code target} to find the highest ancestor that does not yet
     * exist on disk — the exact directory {@link Files#createDirectories} is about
     * to create as the top of its new chain. Registering this (not {@code target}
     * itself) with {@link TempDirs} ensures cleanup removes the entire newly-created
     * chain, not just its deepest leaf, when {@code target} has more than one
     * missing segment (e.g. {@code "parent/child"} where neither exists yet).
     */
    private Path topmostMissingAncestor(Path target) {
        Path current = target;
        Path highestMissing = target;
        while (current != null && !Files.exists(current)) {
            highestMissing = current;
            current = current.getParent();
        }
        return highestMissing;
    }

    static String createPath(String... parts) {
        final StringBuilder buf = new StringBuilder();
        Arrays.asList(parts).forEach(part -> buf.append(OsUtil.separator()).append(part));
        return buf.toString();
    }

    private VaultMarker readMarker(Path vaultContent) throws IOException {
        Path descriptor = vaultContent.resolve(MarkerType.VAULT.folderName()).resolve(MarkerType.DESCRIPTOR_FILE_NAME);
        return (VaultMarker) new VaultMarkerStrategy().deserialize(Files.readString(descriptor));
    }

    // ── load() ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("load()")
    class LoadTests {

        @Test
        void load_existingFile_returnsVaults() throws IOException, VaultException {
            List<Vault> seed = List.of(
                    new Vault(UUID.randomUUID().toString(), "Alice", "vault-1", "/path/1"),
                    new Vault(UUID.randomUUID().toString(), "Alice", "vault-2", "/path/2"));
            JsonMapper.saveVaultsToFile(vaultService.catalogFile, seed);

            vaultService.load();

            for (Vault expected : seed) {
                Optional<Vault> found = vaultService.findById(expected.getId());
                assertThat(found).isPresent();
                assertThat(found.get().getName()).isEqualTo(expected.getName());
                assertThat(found.get().getPath()).isEqualTo(expected.getPath());
            }
        }

        @Test
        void load_missingFile_returnsEmptyList() throws VaultException {
            vaultService.load();
            assertThat(vaultService.findAll().size()).isEqualTo(0);
        }

        @Test
        void load_replacesExistingInMemoryState(TempDirs tempDirs) throws Exception {
            Path oldContent = newVaultDir(tempDirs, "load-replace-old");
            vaultService.create("Alice", "load-replace-old", oldContent.toString());
            assertThat(vaultService.findAll().size()).isEqualTo(1);

            List<Vault> diskState = List.of(
                    new Vault(UUID.randomUUID().toString(), "Alice", "load-replace-new-1", "/new/path/1"),
                    new Vault(UUID.randomUUID().toString(), "Alice", "load-replace-new-2", "/new/path/2"));
            JsonMapper.saveVaultsToFile(vaultService.catalogFile, diskState);

            vaultService.load();

            assertThat(vaultService.findAll().size()).isEqualTo(2);
            assertThat(vaultService.findAllByName("load-replace-old")).asList().isEmpty();
            assertThat(vaultService.findAllByName("load-replace-new-1")).asList().hasSize(1);
        }

        @Test
        void load_duplicateRepoSlugsInFile_throwsVaultException() throws IOException {
            List<Vault> diskState = List.of(
                    new Vault(UUID.randomUUID().toString(), "Alice", "dup-slug", "/path/a"),
                    new Vault(UUID.randomUUID().toString(), "Alice", "dup-slug", "/path/b"));
            JsonMapper.saveVaultsToFile(vaultService.catalogFile, diskState);

            assertThatThrownBy(() -> vaultService.load())
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining("Alice/dup-slug");
        }

        @Test
        void load_sameNameDifferentOwners_doesNotThrow() throws IOException, VaultException {
            List<Vault> diskState = List.of(
                    new Vault(UUID.randomUUID().toString(), "Alice", "shared-name", "/path/a"),
                    new Vault(UUID.randomUUID().toString(), "Bob",  "shared-name", "/path/b"));
            JsonMapper.saveVaultsToFile(vaultService.catalogFile, diskState);

            vaultService.load();

            assertThat(vaultService.findAll().size()).isEqualTo(2);
        }

        @Test
        void load_duplicatePathsInFile_throwsVaultException() throws IOException {
            List<Vault> diskState = List.of(
                    new Vault(UUID.randomUUID().toString(), "Alice", "vault-a", createPath("shared", "path")),
                    new Vault(UUID.randomUUID().toString(), "Bob",  "vault-b", createPath("shared", "path")));
            JsonMapper.saveVaultsToFile(vaultService.catalogFile, diskState);

            assertThatThrownBy(() -> vaultService.load())
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining(createPath("shared", "path"));
        }
    }

    // ── load() — vault marker refresh (delegated to MarkerService) ───────────

    @Nested
    @DisplayName("load() - vault marker refresh")
    class MarkerRefreshTests {

        @Test
        @DisplayName("writes a fresh marker for a vault with no existing marker — seeded directly "
                + "into catalog.json, bypassing create()/claim, since create() now claims "
                + "its own marker immediately and can no longer produce a genuinely markerless vault")
        void load_noExistingMarker_writesFreshMarker(TempDirs tempDirs) throws Exception {
            Path vaultContent = newVaultDir(tempDirs, "marker-fresh");
            Vault vault = new Vault(UUID.randomUUID().toString(), "Alice", "marker-fresh",
                    vaultContent.toString());
            JsonMapper.saveVaultsToFile(vaultService.catalogFile, List.of(vault));

            vaultService.load();

            VaultMarker marker = readMarker(vaultContent);

            assertThat(marker).isNotNull();
            assertThat(marker.id()).isEqualTo(vault.getId());
            assertThat(marker.repoSlug()).isEqualTo("Alice/marker-fresh");
            assertThat(marker.catalogPath()).isEqualTo(vaultService.catalogFile.getPath());
            assertThat(marker.createdAt()).isEqualTo(marker.lastUpdate());
        }

        @Test
        @DisplayName("refreshes lastUpdate but preserves createdAt across repeated load() calls")
        void load_matchingMarker_refreshesTimestampPreservesCreatedAt(TempDirs tempDirs) throws Exception {
            Path vaultContent = newVaultDir(tempDirs, "marker-refresh");
            vaultService.create("Alice", "marker-refresh", vaultContent.toString());
            vaultService.load();

            VaultMarker firstPass = readMarker(vaultContent);

            Thread.sleep(5);
            vaultService.load();

            VaultMarker secondPass = readMarker(vaultContent);

            assertThat(secondPass.id()).isEqualTo(firstPass.id());
            assertThat(secondPass.createdAt()).isEqualTo(firstPass.createdAt());
            assertThat(secondPass.lastUpdate()).isNotNull();
        }

        @Test
        @DisplayName("each vault keeps its own correct marker at its own path — no shared/misplaced marker")
        void load_multipleVaults_eachGetsItsOwnMarkerAtItsOwnPath(TempDirs tempDirs) throws Exception {
            Path vaultAContent = newVaultDir(tempDirs, "marker-vaultA");
            Path vaultBContent = newVaultDir(tempDirs, "marker-vaultB");
            Vault vaultA = vaultService.create("Alice", "marker-multi-a", vaultAContent.toString());
            Vault vaultB = vaultService.create("Bob", "marker-multi-b", vaultBContent.toString());

            vaultService.load();

            VaultMarker markerA = readMarker(vaultAContent);
            VaultMarker markerB = readMarker(vaultBContent);

            assertThat(markerA.id()).isEqualTo(vaultA.getId());
            assertThat(markerB.id()).isEqualTo(vaultB.getId());
            assertThat(Files.exists(Path.of(vaultService.catalogFile.getPath())
                    .resolveSibling(MarkerType.VAULT.folderName()))).isFalse();
        }
    }

    // ── create() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        void create_addsVaultAndPersists(TempDirs tempDirs) throws Exception {
            Path content = newVaultDir(tempDirs, "create-basic");
            Vault vault = vaultService.create("Alice", "create-basic", content.toString());

            assertThat(vaultService.findAll().size()).isEqualTo(1);
            assertThat(vaultService.findById(vault.getId())).isPresent();
            assertThat(vaultService.catalogFile.exists()).isTrue();
        }

        @Test
        void create_generatesUniqueIds(TempDirs tempDirs) throws Exception {
            vaultService.create("Alice", "unique-ids-a", newVaultDir(tempDirs, "unique-a").toString());
            vaultService.create("Alice", "unique-ids-b", newVaultDir(tempDirs, "unique-b").toString());

            List<Vault> all = vaultService.findAll();
            assertThat(all.size()).isEqualTo(2);
            assertThat(all.get(0).getId()).isNotEqualTo(all.get(1).getId());
        }

        @Test
        void create_persistsToFile(TempDirs tempDirs) throws Exception {
            vaultService.create("Alice", "create-persist", newVaultDir(tempDirs, "create-persist").toString());

            List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.catalogFile);
            assertThat(onDisk.stream().anyMatch(v -> v.getName().equals("create-persist"))).isTrue();
        }

        @Test
        void create_duplicateRepoSlug_throwsVaultExceptionAndDoesNotPersist(TempDirs tempDirs) throws Exception {
            vaultService.create("Alice", "dup-repoSlug", newVaultDir(tempDirs, "dup-slug-a").toString());

            assertThatThrownBy(() -> vaultService.create(
                    "Alice", "dup-repoSlug", newVaultDir(tempDirs, "dup-slug-b").toString()))
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining("Alice/dup-repoSlug");

            assertThat(vaultService.findAll().size()).isEqualTo(1);
            List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.catalogFile);
            assertThat(onDisk.size()).isEqualTo(1);
        }

        @Test
        void create_sameNameDifferentOwners_doesNotThrow(TempDirs tempDirs) throws Exception {
            vaultService.create("Alice", "shared-name", newVaultDir(tempDirs, "shared-name-a").toString());
            vaultService.create("Bob",  "shared-name", newVaultDir(tempDirs, "shared-name-b").toString());

            assertThat(vaultService.findAll().size()).isEqualTo(2);
            assertThat(vaultService.findByRepoSlug("Alice/shared-name")).isPresent();
            assertThat(vaultService.findByRepoSlug("Bob/shared-name")).isPresent();
        }

        @Test
        void create_duplicatePath_throwsVaultExceptionAndDoesNotPersist(TempDirs tempDirs) throws Exception {
            Path shared = newVaultDir(tempDirs, "dup-path-shared");
            vaultService.create("Alice", "dup-path-a", shared.toString());

            assertThatThrownBy(() -> vaultService.create("Bob", "dup-path-b", shared.toString()))
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining(shared.toString());

            assertThat(vaultService.findAll().size()).isEqualTo(1);
        }

        @Test
        @DisplayName("throws and does not register when the path is already claimed by another vault")
        void create_pathAlreadyClaimed_throwsAndDoesNotRegister(TempDirs tempDirs) throws Exception {
            Path content = newVaultDir(tempDirs, "create-already-claimed");
            VaultMarker foreign = VaultMarker.create("foreign-id", "Bob/foreign",
                    "/some/other/catalog.json", "2020-01-01T00:00:00");
            markerService.claim(MarkerType.VAULT, content.toString(), foreign);

            assertThatThrownBy(() -> vaultService.create("Alice", "create-already-claimed", content.toString()))
                    .isInstanceOf(VaultException.class);

            assertThat(vaultService.findAll().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("throws and does not register when the path is nested inside an already-claimed directory")
        void create_nestedInsideClaimedDirectory_throwsAndDoesNotRegister(TempDirs tempDirs) throws Exception {
            Path parent = newVaultDir(tempDirs, "create-nesting-parent");
            vaultService.create("Alice", "create-nesting-parent-vault", parent.toString());
            Path child = parent.resolve("child-vault");
            Files.createDirectories(child);

            assertThatThrownBy(() -> vaultService.create("Bob", "create-nesting-child", child.toString()))
                    .isInstanceOf(VaultException.class);

            assertThat(vaultService.findByRepoSlug("Bob/create-nesting-child")).isNotPresent();
        }

        // ── path normalization ──────────────────────────────────────────────

        @Test
        @DisplayName("normalizes a relative path to absolute before storing")
        void create_relativePath_isNormalizedToAbsolute(TempDirs tempDirs) throws Exception {
            String relative = "normalize-create-relative-dir";
            newRelativeVaultDir(tempDirs, relative);

            Vault vault = vaultService.create("Alice", "normalize-create", relative);

            assertThat(vault.getPath()).isEqualTo(TestUtil.absolute(relative));
            assertThat(Path.of(vault.getPath()).isAbsolute()).isTrue();
        }

        @Test
        @DisplayName("collapses redundant segments (e.g. a \".\" component) rather than preserving them verbatim")
        void create_pathWithRedundantSegments_isNormalized(TempDirs tempDirs) throws Exception {
            String messy = String.join(java.io.File.separator,
                    "normalize-redundant-dir", ".", "path");
            newRelativeVaultDir(tempDirs, messy);

            Vault vault = vaultService.create("Alice", "normalize-redundant", messy);

            assertThat(vault.getPath()).isEqualTo(TestUtil.absolute(messy));
            assertThat(vault.getPath()).doesNotContain(
                    java.io.File.separator + "." + java.io.File.separator);
        }

        @Test
        @DisplayName("detects two textually different but equivalent relative paths as a duplicate "
                + "— normalization happens BEFORE the uniqueness check")
        void create_equivalentRelativePaths_detectedAsDuplicate(TempDirs tempDirs) throws Exception {
            newRelativeVaultDir(tempDirs, "equiv-normalize-dir/target");
            vaultService.create("Alice", "equiv-path-a", "equiv-normalize-dir/target");

            assertThatThrownBy(() -> vaultService.create(
                    "Bob", "equiv-path-b", "equiv-normalize-dir/./target"))
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining("duplicated path");
        }
    }

    // ── update() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("update()")
    class UpdateTests {

        @Test
        void update_existingVault_persistsChanges(TempDirs tempDirs) throws Exception {
            Path content = newVaultDir(tempDirs, "update-original");
            Vault vault = vaultService.create("Alice", "update-original", content.toString());
            Vault renamed = new Vault(vault.getId(), vault.getOwner(), "update-renamed", vault.getPath());

            vaultService.update(renamed);

            assertThat(vaultService.findByRepoSlug("Alice/update-renamed")).isPresent();
            assertThat(vaultService.findByRepoSlug("Alice/update-original")).isNotPresent();
        }

        @Test
        void update_nullId_throwsIllegalArgumentException() {
            Vault vault = new Vault(null, "Alice", "no-id", "/some/path");
            assertThatThrownBy(() -> vaultService.update(vault))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("renaming to the vault's own current repoSlug does not throw, and a changed "
                + "path is persisted in its normalized, absolute form")
        void update_sameRepoSlug_doesNotThrow(TempDirs tempDirs) throws Exception {
            Path original = newVaultDir(tempDirs, "update-noop-original");
            Path moved = newVaultDir(tempDirs, "update-noop-moved");
            Vault vault = vaultService.create("Alice", "update-noop", original.toString());
            Vault withNewPath = new Vault(vault.getId(), vault.getOwner(), vault.getName(), moved.toString());

            vaultService.update(withNewPath);

            assertThat(vaultService.findByRepoSlug("Alice/update-noop")).isPresent();
            assertThat(vaultService.findByRepoSlug("Alice/update-noop").get().getPath())
                    .isEqualTo(TestUtil.absolute(moved.toString()));
        }

        @Test
        void update_renameToExistingRepoSlug_throwsVaultExceptionAndDoesNotPersist(TempDirs tempDirs) throws Exception {
            Vault vaultA = vaultService.create("Alice", "update-collision-a", newVaultDir(tempDirs, "collision-a").toString());
            vaultService.create("Alice", "update-collision-b", newVaultDir(tempDirs, "collision-b").toString());

            Vault renamedA = new Vault(vaultA.getId(), vaultA.getOwner(),
                    "update-collision-b", vaultA.getPath());

            assertThatThrownBy(() -> vaultService.update(renamedA))
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining("Alice/update-collision-b");

            List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.catalogFile);
            assertThat(onDisk.stream()
                    .anyMatch(v -> v.getId().equals(vaultA.getId())
                            && v.getName().equals("update-collision-a"))).isTrue();
        }

        @Test
        void update_changeOwnerToExistingRepoSlug_throwsVaultException(TempDirs tempDirs) throws Exception {
            Vault vaultA = vaultService.create("Alice", "owner-collision", newVaultDir(tempDirs, "owner-coll-a").toString());
            vaultService.create("Bob", "owner-collision", newVaultDir(tempDirs, "owner-coll-b").toString());

            Vault changedOwner = new Vault(vaultA.getId(), "Bob", vaultA.getName(), vaultA.getPath());

            assertThatThrownBy(() -> vaultService.update(changedOwner))
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining("Bob/owner-collision");
        }

        @Test
        void update_duplicatePath_throwsVaultException(TempDirs tempDirs) throws Exception {
            Vault vaultA = vaultService.create("Alice", "path-collision-a", newVaultDir(tempDirs, "path-coll-a").toString());
            Path pathB = newVaultDir(tempDirs, "path-coll-b");
            vaultService.create("Alice", "path-collision-b", pathB.toString());

            Vault movedA = new Vault(vaultA.getId(), vaultA.getOwner(), vaultA.getName(), pathB.toString());

            assertThatThrownBy(() -> vaultService.update(movedA))
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining(pathB.toString());
        }

        @Test
        void update_samePathNoOp_doesNotThrow(TempDirs tempDirs) throws Exception {
            Path content = newVaultDir(tempDirs, "path-noop");
            Vault vault = vaultService.create("Alice", "path-noop", content.toString());
            Vault noOp = new Vault(vault.getId(), vault.getOwner(), "path-noop-renamed", vault.getPath());

            vaultService.update(noOp);

            assertThat(vaultService.findByRepoSlug("Alice/path-noop-renamed")).isPresent();
        }

        @Test
        @DisplayName("normalizes a relative path to absolute the same way create() does")
        void update_relativePath_isNormalizedToAbsolute(TempDirs tempDirs) throws Exception {
            Path original = newVaultDir(tempDirs, "normalize-update-original");
            Vault vault = vaultService.create("Alice", "normalize-update", original.toString());

            String relative = "normalize-update-relative-dir";
            newRelativeVaultDir(tempDirs, relative);
            Vault withRelativePath = new Vault(vault.getId(), vault.getOwner(), vault.getName(), relative);

            vaultService.update(withRelativePath);

            assertThat(vaultService.findById(vault.getId()).get().getPath())
                    .isEqualTo(TestUtil.absolute(relative));
        }

        // ── marker claim/release wiring ──────────────────────────────────────

        @Test
        @DisplayName("claims the new path's marker and releases the old one when the path actually changes")
        void update_pathChange_claimsNewAndReleasesOld(TempDirs tempDirs) throws Exception {
            Path oldContent = newVaultDir(tempDirs, "update-claim-old");
            Path newContent = newVaultDir(tempDirs, "update-claim-new");
            Vault vault = vaultService.create("Alice", "update-claim", oldContent.toString());

            Vault moved = new Vault(vault.getId(), vault.getOwner(), vault.getName(), newContent.toString());
            vaultService.update(moved);

            assertThat(Files.exists(oldContent.resolve(MarkerType.VAULT.folderName()))).isFalse();
            VaultMarker newMarker = readMarker(newContent);
            assertThat(newMarker).isNotNull();
            assertThat(newMarker.id()).isEqualTo(vault.getId());
        }

        @Test
        @DisplayName("does not touch any marker when the path is unchanged")
        void update_noPathChange_doesNotTouchMarkers(TempDirs tempDirs) throws Exception {
            Path content = newVaultDir(tempDirs, "update-no-claim");
            Vault vault = vaultService.create("Alice", "update-no-claim", content.toString());
            VaultMarker beforeMarker = readMarker(content);

            Vault renamed = new Vault(vault.getId(), vault.getOwner(), "update-no-claim-renamed", vault.getPath());
            vaultService.update(renamed);

            VaultMarker afterMarker = readMarker(content);
            assertThat(afterMarker).isEqualTo(beforeMarker);
        }
    }

    // ── delete() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        void delete_existingVault_removesAndPersists(TempDirs tempDirs) throws Exception {
            Path content = newVaultDir(tempDirs, "delete-me");
            Vault vault = vaultService.create("Alice", "delete-me", content.toString());

            vaultService.delete(vault.getId());

            assertThat(vaultService.findById(vault.getId())).isNotPresent();
            List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.catalogFile);
            assertThat(onDisk.stream().noneMatch(v -> v.getName().equals("delete-me"))).isTrue();
        }

        @Test
        void delete_nullId_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> vaultService.delete(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void delete_thenCreateWithSameRepoSlug_doesNotThrow(TempDirs tempDirs) throws Exception {
            Vault vault = vaultService.create("Alice", "reusable-slug", newVaultDir(tempDirs, "reusable-slug-a").toString());
            vaultService.delete(vault.getId());

            Vault recreated = vaultService.create("Alice", "reusable-slug", newVaultDir(tempDirs, "reusable-slug-b").toString());

            assertThat(vaultService.findByRepoSlug("Alice/reusable-slug")).isPresent();
            assertThat(vaultService.findByRepoSlug("Alice/reusable-slug").get().getId())
                    .isEqualTo(recreated.getId());
        }

        @Test
        @DisplayName("frees the path for reuse — a deleted vault's directory can be reclaimed by a new vault")
        void delete_thenCreateWithSamePath_doesNotThrow(TempDirs tempDirs) throws Exception {
            Path content = newVaultDir(tempDirs, "reusable-path");
            Vault vault = vaultService.create("Alice", "reusable-path-a", content.toString());
            vaultService.delete(vault.getId());

            Vault recreated = vaultService.create("Alice", "reusable-path-b", content.toString());

            assertThat(vaultService.findById(recreated.getId())).isPresent();
        }

        @Test
        @DisplayName("releases the vault's marker, but leaves the physical directory untouched")
        void delete_releasesMarkerButNotDirectory(TempDirs tempDirs) throws Exception {
            Path content = newVaultDir(tempDirs, "delete-releases-marker");
            Vault vault = vaultService.create("Alice", "delete-releases-marker", content.toString());
            assertThat(Files.exists(content.resolve(MarkerType.VAULT.folderName()))).isTrue();

            vaultService.delete(vault.getId());

            assertThat(Files.exists(content.resolve(MarkerType.VAULT.folderName()))).isFalse();
            assertThat(Files.exists(content)).isTrue();
        }
    }

    // ── findAll() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findAll()")
    class FindAllTests {

        @Test
        void findAll_emptyService_returnsEmptyList() {
            assertThat(vaultService.findAll().size()).isEqualTo(0);
        }

        @Test
        void findAll_returnsDefensiveCopy(TempDirs tempDirs) throws Exception {
            vaultService.create("Alice", "defensive-copy-a", newVaultDir(tempDirs, "defensive-a").toString());
            vaultService.create("Alice", "defensive-copy-b", newVaultDir(tempDirs, "defensive-b").toString());

            List<Vault> copy = vaultService.findAll();
            copy.clear();

            assertThat(vaultService.findAll().size()).isEqualTo(2);
        }
    }

    // ── findById() ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findById()")
    class FindByIdTests {

        @Test
        void findById_existingId_returnsVault(TempDirs tempDirs) throws Exception {
            Vault vault = vaultService.create("Alice", "findById-target", newVaultDir(tempDirs, "findbyid").toString());

            Optional<Vault> found = vaultService.findById(vault.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getName()).isEqualTo("findById-target");
        }

        @Test
        void findById_unknownId_returnsEmpty() {
            assertThat(vaultService.findById("non-existent-id")).isNotPresent();
        }
    }

    // ── findByRepoSlug() ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("findByRepoSlug()")
    class FindByRepoSlugTests {

        @Test
        void findByRepoSlug_existingSlug_returnsVault(TempDirs tempDirs) throws Exception {
            vaultService.create("Alice", "slug-target", newVaultDir(tempDirs, "slug-target").toString());

            Optional<Vault> found = vaultService.findByRepoSlug("Alice/slug-target");

            assertThat(found).isPresent();
            assertThat(found.get().getOwner()).isEqualTo("Alice");
            assertThat(found.get().getName()).isEqualTo("slug-target");
        }

        @Test
        void findByRepoSlug_unknownSlug_returnsEmpty() {
            assertThat(vaultService.findByRepoSlug("Alice/non-existent")).isNotPresent();
        }

        @Test
        void findByRepoSlug_partialMatch_returnsEmpty(TempDirs tempDirs) throws Exception {
            vaultService.create("Alice", "partial-slug", newVaultDir(tempDirs, "partial-slug").toString());

            assertThat(vaultService.findByRepoSlug("partial-slug")).isNotPresent();
            assertThat(vaultService.findByRepoSlug("Alice")).isNotPresent();
            assertThat(vaultService.findByRepoSlug("Bob/partial-slug")).isNotPresent();
        }
    }

    // ── findAllByName() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("findAllByName()")
    class FindAllByNameTests {

        @Test
        void findAllByName_noMatch_returnsEmptyList(TempDirs tempDirs) throws Exception {
            vaultService.create("Alice", "findall-existing", newVaultDir(tempDirs, "findall-existing").toString());

            assertThat(vaultService.findAllByName("no-such-name")).asList().isEmpty();
        }

        @Test
        void findAllByName_singleMatch_returnsOneElement(TempDirs tempDirs) throws Exception {
            vaultService.create("Alice", "findall-single", newVaultDir(tempDirs, "findall-single").toString());

            List<Vault> result = vaultService.findAllByName("findall-single");

            assertThat(result.size()).isEqualTo(1);
            assertThat(result.get(0).getOwner()).isEqualTo("Alice");
        }

        @Test
        void findAllByName_multipleOwnersSameName_returnsAll(TempDirs tempDirs) throws Exception {
            vaultService.create("Alice", "findall-ambiguous", newVaultDir(tempDirs, "findall-ambig-a").toString());
            vaultService.create("Bob",  "findall-ambiguous", newVaultDir(tempDirs, "findall-ambig-b").toString());

            List<Vault> result = vaultService.findAllByName("findall-ambiguous");

            assertThat(result.size()).isEqualTo(2);
            assertThat(result.stream().anyMatch(v -> v.getOwner().equals("Alice"))).isTrue();
            assertThat(result.stream().anyMatch(v -> v.getOwner().equals("Bob"))).isTrue();
        }

        @Test
        void findAllByName_returnsDefensiveCopy(TempDirs tempDirs) throws Exception {
            vaultService.create("Alice", "findall-defensive", newVaultDir(tempDirs, "findall-defensive").toString());

            List<Vault> copy = vaultService.findAllByName("findall-defensive");
            copy.clear();

            assertThat(vaultService.findAllByName("findall-defensive").size()).isEqualTo(1);
        }
    }

    // ── makeVaultSnapshot() — naming and scoping ─────────────────────────────

    @Nested
    @DisplayName("makeVaultSnapshot()")
    class MakeVaultSnapshotTests {

        @Test
        @DisplayName("the snapshot folder name replaces \"/\" in the repoSlug with \"_\" — "
                + "a raw \"/\" would make Path#resolve create a nested owner/ subfolder "
                + "instead of a flat name, breaking the non-recursive FIFO scan")
        void makeVaultSnapshot_repoSlugWithSlash_producesFlatFolderName(TempDirs tempDirs) throws Exception {
            Path vaultContent = newVaultDir(tempDirs, "snapshot-naming");
            Vault vaultA = vaultService.create("Alice", "snapshot-naming", vaultContent.toString());

            vaultService.makeVaultSnapshot(vaultA);

            Path backupsRoot = testVault.vaultPath().resolve(VaultService.BACKUPS_FOLDER_NAME);
            try (var stream = Files.list(backupsRoot)) {
                assertThat(stream.anyMatch(p -> p.getFileName().toString()
                        .startsWith("Alice_snapshot-naming_"))).isTrue();
            }
            assertThat(Files.isDirectory(backupsRoot.resolve("Alice"))).isFalse();
        }

        @Test
        @DisplayName("two vaults with the same name but different owners produce distinct "
                + "snapshot prefixes — the exact collision the repoSlug-based prefix prevents")
        void makeVaultSnapshot_sameNameDifferentOwners_doesNotCollide(TempDirs tempDirs) throws Exception {
            Path aliceContent = newVaultDir(tempDirs, "snapshot-alice");
            Path bobContent   = newVaultDir(tempDirs, "snapshot-bob");
            Vault vaultAlice = vaultService.create("Alice", "shared-vault-name", aliceContent.toString());
            Vault vaultBob   = vaultService.create("Bob", "shared-vault-name", bobContent.toString());

            vaultService.makeVaultSnapshot(vaultAlice);
            vaultService.makeVaultSnapshot(vaultBob);

            Path backupsRoot = testVault.vaultPath().resolve(VaultService.BACKUPS_FOLDER_NAME);
            try (var stream = Files.list(backupsRoot)) {
                List<String> names = stream.map(p -> p.getFileName().toString()).toList();
                assertThat(names.stream().anyMatch(n -> n.startsWith("Alice_shared-vault-name_"))).isTrue();
                assertThat(names.stream().anyMatch(n -> n.startsWith("Bob_shared-vault-name_"))).isTrue();
            }
        }
    }
}
