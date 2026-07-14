package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.exception.VaultException;
import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.vault.Vault;
import io.aledep10.nomadsync.util.*;
import io.aledep10.nomadsync.vault.VaultMarker;
import org.junit.jupiter.api.*;

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
 * {@link LoadTests}, {@link MarkerRefreshTests}, {@link CheckNoNestingConflictTests},
 * {@link ClaimVaultPathTests}, {@link CreateTests}, {@link UpdateTests},
 * {@link DeleteTests}, the {@code find*} groups, and {@link MakeVaultSnapshotTests}.
 * No flat, ungrouped test methods remain at the top level.</p>
 *
 * <h2>Two vault creation patterns — why both?</h2>
 * <p>{@code vaultService.create(owner, name, path)} tests the service end-to-end:
 * the service generates the UUID, persists, claims the {@code .vault} marker, and
 * returns the domain object. This is the normal case for CRUD and query tests.</p>
 *
 * <p>{@code new Vault(UUID, owner, name, path)} + {@link JsonMapper#saveVaultsToFile}
 * is used only in {@link LoadTests} where the goal is to verify that the service
 * correctly reads a file written by a previous session — and in the one
 * {@link MarkerRefreshTests} case that specifically needs a vault with <em>no</em>
 * existing marker (see that test's own note: {@code create()} now claims its own
 * marker immediately, so it can no longer produce that starting state).</p>
 *
 * <h2>Real directories are now mandatory for {@code create()}/{@code update()}</h2>
 * <p>{@link VaultService#claimVaultPath} atomically reserves the {@code .vault}
 * marker file via {@code Files.createFile} — this requires the target directory
 * to already exist on disk. Every test that calls {@code create()} or moves a
 * vault's path via {@code update()} therefore needs a real backing directory, not
 * a literal string like {@code "/some/path"} (which sufficed before the marker
 * feature existed). Use {@link #newVaultDir} for this — it creates a temp
 * directory <em>and</em> registers it for automatic cleanup in {@code @AfterEach},
 * even if the test fails before reaching its own assertions. This fixes a real
 * leak in the previous version of this file: several tests only cleaned up their
 * temp directory on the last line, so a failed assertion earlier in the same test
 * left the directory behind on every single failure.</p>
 *
 * <p>A handful of tests deliberately exercise CWD-relative path normalization by
 * passing a <em>relative</em> string straight to {@code create()}/{@code update()}.
 * Since the resolved absolute location must exist for the claim to succeed, these
 * use {@link #newRelativeVaultDir}, which creates the directory at the resolved
 * location (relative to the JVM's working directory — typically the project root
 * during a test run) and registers it for cleanup the same way. This is a
 * deliberate, contained trade-off to test real CWD-relative resolution rather
 * than mocking it away.</p>
 *
 * <p>Tests in {@link LoadTests} that seed {@code vaults.json} directly via
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
class VaultServiceTest {

    static TestVault testVault;
    static LogService logService;

    GitignoreService gitignoreService;
    VaultService vaultService;
    private final List<Path> tempDirs = new ArrayList<>();

    @BeforeAll
    static void prepareSharedState() throws IOException {
        testVault  = TestUtil.getTestVault("VaultServiceTest");
        logService = new LogService(TestUtil.forLogService(testVault, LogLevel.DEBUG), testVault.rootPath());
    }

    @BeforeEach
    void setUp() throws IOException {
        gitignoreService = new GitignoreService(logService);
        Properties properties = TestUtil.forVaultService(testVault);
        vaultService = new VaultService(properties, testVault.vaultPath(), gitignoreService, logService);
        tempDirs.clear();
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Path dir : tempDirs) {
            FileUtil.deleteRecursively(dir);
        }
        tempDirs.clear();
        TestUtil.cleanup(testVault);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a fresh temp directory for a vault's content and registers it for
     * automatic cleanup in {@code @AfterEach} — required for any test that calls
     * {@code create()}/{@code update()} with a path that changes, since
     * {@link VaultService#claimVaultPath} needs a real directory to claim.
     */
    private Path newVaultDir(String prefix) throws IOException {
        Path dir = Files.createTempDirectory("nomadsync-" + prefix);
        tempDirs.add(dir);
        return dir;
    }

    /**
     * Creates a real directory at the CWD-resolved location of {@code relative}
     * (via {@link TestUtil#absolute(String)}) and registers it for cleanup — used
     * only by tests that deliberately exercise CWD-relative path normalization by
     * passing a relative string to {@code create()}/{@code update()}.
     */
    private Path newRelativeVaultDir(String relative) throws IOException {
        Path resolved = Path.of(TestUtil.absolute(relative));
        Files.createDirectories(resolved);
        tempDirs.add(resolved);
        return resolved;
    }

    /**
     * Builds an OS-native, non-existent path literal — valid only for tests that
     * never call {@code create()}/{@code update()} on it (e.g. {@link LoadTests}
     * seeding {@code vaults.json} directly, where no real directory is required).
     */
    static String createPath(String... parts) {
        final StringBuilder buf = new StringBuilder();
        Arrays.asList(parts).forEach(part -> buf.append(OsUtil.separator()).append(part));
        return buf.toString();
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
            JsonMapper.saveVaultsToFile(vaultService.vaultFile, seed);

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
        void load_replacesExistingInMemoryState() throws Exception {
            Path oldContent = newVaultDir("load-replace-old");
            vaultService.create("Alice", "load-replace-old", oldContent.toString());
            assertThat(vaultService.findAll().size()).isEqualTo(1);

            List<Vault> diskState = List.of(
                    new Vault(UUID.randomUUID().toString(), "Alice", "load-replace-new-1", "/new/path/1"),
                    new Vault(UUID.randomUUID().toString(), "Alice", "load-replace-new-2", "/new/path/2"));
            JsonMapper.saveVaultsToFile(vaultService.vaultFile, diskState);

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
            JsonMapper.saveVaultsToFile(vaultService.vaultFile, diskState);

            assertThatThrownBy(() -> vaultService.load())
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining("Alice/dup-slug");
        }

        @Test
        void load_sameNameDifferentOwners_doesNotThrow() throws IOException, VaultException {
            List<Vault> diskState = List.of(
                    new Vault(UUID.randomUUID().toString(), "Alice", "shared-name", "/path/a"),
                    new Vault(UUID.randomUUID().toString(), "Bob",  "shared-name", "/path/b"));
            JsonMapper.saveVaultsToFile(vaultService.vaultFile, diskState);

            vaultService.load();

            assertThat(vaultService.findAll().size()).isEqualTo(2);
        }

        @Test
        void load_duplicatePathsInFile_throwsVaultException() throws IOException {
            List<Vault> diskState = List.of(
                    new Vault(UUID.randomUUID().toString(), "Alice", "vault-a", createPath("shared", "path")),
                    new Vault(UUID.randomUUID().toString(), "Bob",  "vault-b", createPath("shared", "path")));
            JsonMapper.saveVaultsToFile(vaultService.vaultFile, diskState);

            assertThatThrownBy(() -> vaultService.load())
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining(createPath("shared", "path"));
        }
    }

    // ── load() — vault marker refresh ────────────────────────────────────────

    @Nested
    @DisplayName("load() - vault marker refresh")
    class MarkerRefreshTests {

        @Test
        @DisplayName("writes a fresh marker for a vault with no existing marker — seeded directly "
                + "into vaults.json, bypassing create()/claimVaultPath, since create() now claims "
                + "its own marker immediately and can no longer produce a genuinely markerless vault")
        void load_noExistingMarker_writesFreshMarker() throws Exception {
            Path vaultContent = newVaultDir("marker-fresh");
            Vault vault = new Vault(UUID.randomUUID().toString(), "Alice", "marker-fresh",
                    vaultContent.toString());
            JsonMapper.saveVaultsToFile(vaultService.vaultFile, List.of(vault));

            vaultService.load();

            VaultMarker marker = JsonMapper.loadVaultMarkerFromFile(
                    vaultContent.resolve(".vault").toFile());

            assertThat(marker).isNotNull();
            assertThat(marker.id()).isEqualTo(vault.getId());
            assertThat(marker.repoSlug()).isEqualTo("Alice/marker-fresh");
            assertThat(marker.jsonPath()).isEqualTo(vaultService.vaultFile.getPath());
            assertThat(marker.createdAt()).isEqualTo(marker.lastUpdate());
        }

        @Test
        @DisplayName("refreshes lastUpdate but preserves createdAt across repeated load() calls")
        void load_matchingMarker_refreshesTimestampPreservesCreatedAt() throws Exception {
            Path vaultContent = newVaultDir("marker-refresh");
            vaultService.create("Alice", "marker-refresh", vaultContent.toString());
            // create() already claimed the marker — this first load() is already a
            // "refresh" of an existing marker, not its creation.
            vaultService.load();

            Path markerPath = vaultContent.resolve(".vault");
            VaultMarker firstPass = JsonMapper.loadVaultMarkerFromFile(markerPath.toFile());

            Thread.sleep(5);
            vaultService.load();

            VaultMarker secondPass = JsonMapper.loadVaultMarkerFromFile(markerPath.toFile());

            assertThat(secondPass.id()).isEqualTo(firstPass.id());
            assertThat(secondPass.createdAt()).isEqualTo(firstPass.createdAt());
            assertThat(secondPass.lastUpdate()).isNotNull();
        }

        @Test
        @DisplayName("does not overwrite a marker that belongs to a different vault id — logs a conflict instead")
        void load_conflictingMarkerFromDifferentVault_doesNotOverwrite() throws Exception {
            Path vaultContent = newVaultDir("marker-conflict");
            vaultService.create("Alice", "marker-conflict", vaultContent.toString());
            Path markerPath = vaultContent.resolve(".vault");

            VaultMarker foreign = VaultMarker.create("some-other-id", "Bob/unrelated",
                    "/some/other/vaults.json", "2020-01-01T00:00:00");
            JsonMapper.saveVaultMarkerToFile(markerPath.toFile(), foreign);

            vaultService.load();

            VaultMarker afterLoad = JsonMapper.loadVaultMarkerFromFile(markerPath.toFile());
            assertThat(afterLoad).isEqualTo(foreign);
        }

        @Test
        @DisplayName("does not throw when an existing marker file is corrupt, and load() still succeeds overall")
        void load_corruptMarkerFile_doesNotThrowAndLoadStillSucceeds() throws Exception {
            Path vaultContent = newVaultDir("marker-corrupt");
            Vault vault = vaultService.create("Alice", "marker-corrupt", vaultContent.toString());
            Path markerPath = vaultContent.resolve(".vault");
            Files.writeString(markerPath, "{ this is not valid JSON");

            List<Vault> loaded = vaultService.load();

            assertThat(loaded.stream().anyMatch(v -> v.getId().equals(vault.getId()))).isTrue();
        }

        @Test
        @DisplayName("each vault keeps its own correct marker at its own path — no shared/misplaced marker")
        void load_multipleVaults_eachGetsItsOwnMarkerAtItsOwnPath() throws Exception {
            Path vaultAContent = newVaultDir("marker-vaultA");
            Path vaultBContent = newVaultDir("marker-vaultB");
            Vault vaultA = vaultService.create("Alice", "marker-multi-a", vaultAContent.toString());
            Vault vaultB = vaultService.create("Bob", "marker-multi-b", vaultBContent.toString());

            vaultService.load();

            VaultMarker markerA = JsonMapper.loadVaultMarkerFromFile(
                    vaultAContent.resolve(".vault").toFile());
            VaultMarker markerB = JsonMapper.loadVaultMarkerFromFile(
                    vaultBContent.resolve(".vault").toFile());

            assertThat(markerA.id()).isEqualTo(vaultA.getId());
            assertThat(markerB.id()).isEqualTo(vaultB.getId());
            assertThat(Files.exists(Path.of(vaultService.vaultFile.getPath())
                    .resolveSibling(".vault"))).isFalse();
        }
    }

    // ── checkNoNestingConflict() ─────────────────────────────────────────────

    @Nested
    @DisplayName("checkNoNestingConflict")
    class CheckNoNestingConflictTests {

        private VaultService vaultServiceWithDepth(int depth) throws IOException {
            Properties properties = TestUtil.forVaultService(testVault);
            properties.setProperty("path.maxNestingDepth", String.valueOf(depth));
            return new VaultService(properties, testVault.vaultPath(), gitignoreService, logService);
        }

        @Test
        @DisplayName("does not throw when no marker exists anywhere near the candidate path")
        void noMarkersAnywhere_doesNotThrow() throws Exception {
            Path candidate = newVaultDir("nesting-none").resolve("brand-new-vault");

            vaultService.checkNoNestingConflict(candidate.toString());
            // no exception = pass
        }

        @Test
        @DisplayName("does not require the candidate path to exist on disk")
        void candidatePathDoesNotExist_stillDoesNotThrow() throws Exception {
            Path nonExistent = newVaultDir("nesting-parent")
                    .resolve("does-not-exist-yet").resolve("nested");

            vaultService.checkNoNestingConflict(nonExistent.toString());
            // no exception = pass
        }

        @Test
        @DisplayName("throws when an ancestor directory carries a marker for a different vault")
        void ancestorHasMarker_throws() throws Exception {
            Path parent = newVaultDir("nesting-ancestor");
            JsonMapper.saveVaultMarkerToFile(parent.resolve(".vault").toFile(),
                    VaultMarker.create("ancestor-id", "Alice/ancestor-vault",
                            "/some/vaults.json", "2026-01-01T00:00:00"));
            Path candidate = parent.resolve("child-vault");

            assertThatThrownBy(() -> vaultService.checkNoNestingConflict(candidate.toString()))
                    .isInstanceOf(VaultException.class);
        }

        @Test
        @DisplayName("does not check the candidate path itself — only ancestors and descendants")
        void candidateItselfMarked_isNotCheckedByNestingScan() throws Exception {
            Path candidate = newVaultDir("nesting-self");
            JsonMapper.saveVaultMarkerToFile(candidate.resolve(".vault").toFile(),
                    VaultMarker.create("self-id", "Alice/self-vault",
                            "/some/vaults.json", "2026-01-01T00:00:00"));

            vaultService.checkNoNestingConflict(candidate.toString());
            // no exception = pass — the candidate's own marker is out of scope here
        }

        @Test
        @DisplayName("throws when a descendant within the configured depth carries a marker")
        void descendantWithinDepth_throws() throws Exception {
            VaultService gs = vaultServiceWithDepth(3);
            Path candidate = newVaultDir("nesting-descendant");
            Path deepChild = candidate.resolve("level1").resolve("level2");
            Files.createDirectories(deepChild);
            JsonMapper.saveVaultMarkerToFile(deepChild.resolve(".vault").toFile(),
                    VaultMarker.create("descendant-id", "Bob/descendant-vault",
                            "/some/vaults.json", "2026-01-01T00:00:00"));

            assertThatThrownBy(() -> gs.checkNoNestingConflict(candidate.toString()))
                    .isInstanceOf(VaultException.class);
        }

        @Test
        @DisplayName("does not throw when the marked descendant is beyond the configured depth")
        void descendantBeyondDepth_doesNotThrow() throws Exception {
            VaultService gs = vaultServiceWithDepth(2);
            Path candidate = newVaultDir("nesting-toodeep");
            Path tooDeep = candidate.resolve("level1").resolve("level2").resolve("level3");
            Files.createDirectories(tooDeep);
            JsonMapper.saveVaultMarkerToFile(tooDeep.resolve(".vault").toFile(),
                    VaultMarker.create("toodeep-id", "Bob/toodeep-vault",
                            "/some/vaults.json", "2026-01-01T00:00:00"));

            gs.checkNoNestingConflict(candidate.toString());
            // no exception = pass — level3 marker is outside a depth-2 scan
        }

        @Test
        @DisplayName("default depth (6, no property override) catches a marker exactly at level 6")
        void defaultDepth_markerAtLevelSix_isCaught() throws Exception {
            Path candidate = newVaultDir("nesting-default-6");
            Path level6 = candidate;
            for (int i = 1; i <= 6; i++) level6 = level6.resolve("level" + i);
            Files.createDirectories(level6);
            JsonMapper.saveVaultMarkerToFile(level6.resolve(".vault").toFile(),
                    VaultMarker.create("level6-id", "Bob/level6-vault",
                            "/some/vaults.json", "2026-01-01T00:00:00"));

            assertThatThrownBy(() -> vaultService.checkNoNestingConflict(candidate.toString()))
                    .isInstanceOf(VaultException.class);
        }

        @Test
        @DisplayName("default depth (6, no property override) does not catch a marker at level 7")
        void defaultDepth_markerAtLevelSeven_isNotCaught() throws Exception {
            Path candidate = newVaultDir("nesting-default-7");
            Path level7 = candidate;
            for (int i = 1; i <= 7; i++) level7 = level7.resolve("level" + i);
            Files.createDirectories(level7);
            JsonMapper.saveVaultMarkerToFile(level7.resolve(".vault").toFile(),
                    VaultMarker.create("level7-id", "Bob/level7-vault",
                            "/some/vaults.json", "2026-01-01T00:00:00"));

            vaultService.checkNoNestingConflict(candidate.toString());
            // no exception = pass — level7 is beyond the default depth of 6
        }
    }

    // ── claimVaultPath() ─────────────────────────────────────────────────────

    /**
     * Unit-tests {@code claimVaultPath} in isolation, against manually
     * constructed {@link Vault} objects — <strong>not</strong> via
     * {@code vaultService.create(...)}, which now calls {@code claimVaultPath}
     * internally as part of its own flow. Routing these tests through
     * {@code create()} would mean every claim attempt here fights against a
     * marker {@code create()} already wrote a moment earlier. The wiring of
     * {@code claimVaultPath} into {@code create()}/{@code update()} is verified
     * separately, in {@link CreateTests} and {@link UpdateTests}.
     */
    @Nested
    @DisplayName("claimVaultPath")
    class ClaimVaultPathTests {

        @Test
        @DisplayName("writes a fresh marker when the path is unclaimed and has no nesting conflicts")
        void freshPath_claimsSuccessfully() throws Exception {
            Path vaultContent = newVaultDir("claim-fresh");
            Vault vault = new Vault(UUID.randomUUID().toString(), "Alice", "claim-fresh",
                    vaultContent.toString());

            vaultService.claimVaultPath(vault);

            VaultMarker marker = JsonMapper.loadVaultMarkerFromFile(
                    vaultContent.resolve(".vault").toFile());
            assertThat(marker).isNotNull();
            assertThat(marker.id()).isEqualTo(vault.getId());
            assertThat(marker.repoSlug()).isEqualTo("Alice/claim-fresh");
            assertThat(marker.createdAt()).isEqualTo(marker.lastUpdate());
        }

        @Test
        @DisplayName("throws and does not overwrite when the exact path is already claimed by another vault")
        void exactPathAlreadyClaimed_throwsAndDoesNotOverwrite() throws Exception {
            Path vaultContent = newVaultDir("claim-exact-conflict");
            VaultMarker foreign = VaultMarker.create("foreign-id", "Bob/foreign-vault",
                    "/some/other/vaults.json", "2020-01-01T00:00:00");
            JsonMapper.saveVaultMarkerToFile(vaultContent.resolve(".vault").toFile(), foreign);

            Vault vault = new Vault(UUID.randomUUID().toString(), "Alice", "claim-exact-conflict",
                    vaultContent.toString());

            assertThatThrownBy(() -> vaultService.claimVaultPath(vault))
                    .isInstanceOf(VaultException.class);

            VaultMarker afterAttempt = JsonMapper.loadVaultMarkerFromFile(
                    vaultContent.resolve(".vault").toFile());
            assertThat(afterAttempt).isEqualTo(foreign);
        }

        @Test
        @DisplayName("a second claim attempt on the same already-claimed-by-self path still fails "
                + "— re-confirming an existing marker is load()'s job, not claim's")
        void secondClaimOnOwnPath_alsoFails() throws Exception {
            Path vaultContent = newVaultDir("claim-twice");
            Vault vault = new Vault(UUID.randomUUID().toString(), "Alice", "claim-twice",
                    vaultContent.toString());

            vaultService.claimVaultPath(vault); // first claim — succeeds

            assertThatThrownBy(() -> vaultService.claimVaultPath(vault))
                    .isInstanceOf(VaultException.class);
        }

        @Test
        @DisplayName("throws when an ancestor directory already carries a marker (delegates to checkNoNestingConflict)")
        void ancestorConflict_throws() throws Exception {
            Path parent = newVaultDir("claim-ancestor");
            JsonMapper.saveVaultMarkerToFile(parent.resolve(".vault").toFile(),
                    VaultMarker.create("ancestor-id", "Bob/ancestor-vault",
                            "/some/vaults.json", "2026-01-01T00:00:00"));
            Path childContent = parent.resolve("child-vault");
            Files.createDirectories(childContent);

            Vault vault = new Vault(UUID.randomUUID().toString(), "Alice", "claim-ancestor-conflict",
                    childContent.toString());

            assertThatThrownBy(() -> vaultService.claimVaultPath(vault))
                    .isInstanceOf(VaultException.class);

            assertThat(Files.exists(childContent.resolve(".vault"))).isFalse();
        }

        @Test
        @DisplayName("throws when a descendant within the configured depth already carries a marker")
        void descendantConflict_throws() throws Exception {
            Path vaultContent = newVaultDir("claim-descendant");
            Path nested = vaultContent.resolve("sub").resolve("deeper");
            Files.createDirectories(nested);
            JsonMapper.saveVaultMarkerToFile(nested.resolve(".vault").toFile(),
                    VaultMarker.create("nested-id", "Bob/nested-vault",
                            "/some/vaults.json", "2026-01-01T00:00:00"));

            Vault vault = new Vault(UUID.randomUUID().toString(), "Alice", "claim-descendant-conflict",
                    vaultContent.toString());

            assertThatThrownBy(() -> vaultService.claimVaultPath(vault))
                    .isInstanceOf(VaultException.class);

            assertThat(Files.exists(vaultContent.resolve(".vault"))).isFalse();
        }
    }

    // ── create() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        void create_addsVaultAndPersists() throws Exception {
            Path content = newVaultDir("create-basic");
            Vault vault = vaultService.create("Alice", "create-basic", content.toString());

            assertThat(vaultService.findAll().size()).isEqualTo(1);
            assertThat(vaultService.findById(vault.getId())).isPresent();
            assertThat(vaultService.vaultFile.exists()).isTrue();
        }

        @Test
        void create_generatesUniqueIds() throws Exception {
            vaultService.create("Alice", "unique-ids-a", newVaultDir("unique-a").toString());
            vaultService.create("Alice", "unique-ids-b", newVaultDir("unique-b").toString());

            List<Vault> all = vaultService.findAll();
            assertThat(all.size()).isEqualTo(2);
            assertThat(all.get(0).getId()).isNotEqualTo(all.get(1).getId());
        }

        @Test
        void create_persistsToFile() throws Exception {
            vaultService.create("Alice", "create-persist", newVaultDir("create-persist").toString());

            List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.vaultFile);
            assertThat(onDisk.stream().anyMatch(v -> v.getName().equals("create-persist"))).isTrue();
        }

        @Test
        void create_duplicateRepoSlug_throwsVaultExceptionAndDoesNotPersist() throws Exception {
            vaultService.create("Alice", "dup-repoSlug", newVaultDir("dup-slug-a").toString());

            assertThatThrownBy(() -> vaultService.create(
                    "Alice", "dup-repoSlug", newVaultDir("dup-slug-b").toString()))
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining("Alice/dup-repoSlug");

            assertThat(vaultService.findAll().size()).isEqualTo(1);
            List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.vaultFile);
            assertThat(onDisk.size()).isEqualTo(1);
        }

        @Test
        void create_sameNameDifferentOwners_doesNotThrow() throws Exception {
            vaultService.create("Alice", "shared-name", newVaultDir("shared-name-a").toString());
            vaultService.create("Bob",  "shared-name", newVaultDir("shared-name-b").toString());

            assertThat(vaultService.findAll().size()).isEqualTo(2);
            assertThat(vaultService.findByRepoSlug("Alice/shared-name")).isPresent();
            assertThat(vaultService.findByRepoSlug("Bob/shared-name")).isPresent();
        }

        @Test
        void create_duplicatePath_throwsVaultExceptionAndDoesNotPersist() throws Exception {
            Path shared = newVaultDir("dup-path-shared");
            vaultService.create("Alice", "dup-path-a", shared.toString());

            assertThatThrownBy(() -> vaultService.create("Bob", "dup-path-b", shared.toString()))
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining(shared.toString());

            assertThat(vaultService.findAll().size()).isEqualTo(1);
        }

        @Test
        @DisplayName("throws and does not register when the path is already claimed by another vault")
        void create_pathAlreadyClaimed_throwsAndDoesNotRegister() throws Exception {
            Path content = newVaultDir("create-already-claimed");
            JsonMapper.saveVaultMarkerToFile(content.resolve(".vault").toFile(),
                    VaultMarker.create("foreign-id", "Bob/foreign",
                            "/some/other/vaults.json", "2020-01-01T00:00:00"));

            assertThatThrownBy(() -> vaultService.create("Alice", "create-already-claimed", content.toString()))
                    .isInstanceOf(VaultException.class);

            assertThat(vaultService.findAll().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("throws and does not register when the path is nested inside an already-claimed directory")
        void create_nestedInsideClaimedDirectory_throwsAndDoesNotRegister() throws Exception {
            Path parent = newVaultDir("create-nesting-parent");
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
        void create_relativePath_isNormalizedToAbsolute() throws Exception {
            String relative = "normalize-create-relative-dir";
            newRelativeVaultDir(relative);

            Vault vault = vaultService.create("Alice", "normalize-create", relative);

            assertThat(vault.getPath()).isEqualTo(TestUtil.absolute(relative));
            assertThat(Path.of(vault.getPath()).isAbsolute()).isTrue();
        }

        @Test
        @DisplayName("collapses redundant segments (e.g. a \".\" component) rather than preserving them verbatim")
        void create_pathWithRedundantSegments_isNormalized() throws Exception {
            String messy = String.join(java.io.File.separator,
                    "normalize-redundant-dir", ".", "path");
            newRelativeVaultDir(messy);

            Vault vault = vaultService.create("Alice", "normalize-redundant", messy);

            assertThat(vault.getPath()).isEqualTo(TestUtil.absolute(messy));
            assertThat(vault.getPath()).doesNotContain(
                    java.io.File.separator + "." + java.io.File.separator);
        }

        @Test
        @DisplayName("detects two textually different but equivalent relative paths as a duplicate "
                + "— normalization happens BEFORE the uniqueness check")
        void create_equivalentRelativePaths_detectedAsDuplicate() throws Exception {
            newRelativeVaultDir("equiv-normalize-dir/target");
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
        void update_existingVault_persistsChanges() throws Exception {
            Path content = newVaultDir("update-original");
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
        void update_sameRepoSlug_doesNotThrow() throws Exception {
            Path original = newVaultDir("update-noop-original");
            Path moved = newVaultDir("update-noop-moved");
            Vault vault = vaultService.create("Alice", "update-noop", original.toString());
            Vault withNewPath = new Vault(vault.getId(), vault.getOwner(), vault.getName(), moved.toString());

            vaultService.update(withNewPath);

            assertThat(vaultService.findByRepoSlug("Alice/update-noop")).isPresent();
            assertThat(vaultService.findByRepoSlug("Alice/update-noop").get().getPath())
                    .isEqualTo(TestUtil.absolute(moved.toString()));
        }

        @Test
        void update_renameToExistingRepoSlug_throwsVaultExceptionAndDoesNotPersist() throws Exception {
            Vault vaultA = vaultService.create("Alice", "update-collision-a", newVaultDir("collision-a").toString());
            vaultService.create("Alice", "update-collision-b", newVaultDir("collision-b").toString());

            Vault renamedA = new Vault(vaultA.getId(), vaultA.getOwner(),
                    "update-collision-b", vaultA.getPath());

            assertThatThrownBy(() -> vaultService.update(renamedA))
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining("Alice/update-collision-b");

            List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.vaultFile);
            assertThat(onDisk.stream()
                    .anyMatch(v -> v.getId().equals(vaultA.getId())
                            && v.getName().equals("update-collision-a"))).isTrue();
        }

        @Test
        void update_changeOwnerToExistingRepoSlug_throwsVaultException() throws Exception {
            Vault vaultA = vaultService.create("Alice", "owner-collision", newVaultDir("owner-coll-a").toString());
            vaultService.create("Bob", "owner-collision", newVaultDir("owner-coll-b").toString());

            Vault changedOwner = new Vault(vaultA.getId(), "Bob", vaultA.getName(), vaultA.getPath());

            assertThatThrownBy(() -> vaultService.update(changedOwner))
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining("Bob/owner-collision");
        }

        @Test
        void update_duplicatePath_throwsVaultException() throws Exception {
            Vault vaultA = vaultService.create("Alice", "path-collision-a", newVaultDir("path-coll-a").toString());
            Path pathB = newVaultDir("path-coll-b");
            vaultService.create("Alice", "path-collision-b", pathB.toString());

            Vault movedA = new Vault(vaultA.getId(), vaultA.getOwner(), vaultA.getName(), pathB.toString());

            assertThatThrownBy(() -> vaultService.update(movedA))
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining(pathB.toString());
        }

        @Test
        void update_samePathNoOp_doesNotThrow() throws Exception {
            Path content = newVaultDir("path-noop");
            Vault vault = vaultService.create("Alice", "path-noop", content.toString());
            Vault noOp = new Vault(vault.getId(), vault.getOwner(), "path-noop-renamed", vault.getPath());

            vaultService.update(noOp);

            assertThat(vaultService.findByRepoSlug("Alice/path-noop-renamed")).isPresent();
        }

        @Test
        @DisplayName("normalizes a relative path to absolute the same way create() does")
        void update_relativePath_isNormalizedToAbsolute() throws Exception {
            Path original = newVaultDir("normalize-update-original");
            Vault vault = vaultService.create("Alice", "normalize-update", original.toString());

            String relative = "normalize-update-relative-dir";
            newRelativeVaultDir(relative);
            Vault withRelativePath = new Vault(vault.getId(), vault.getOwner(), vault.getName(), relative);

            vaultService.update(withRelativePath);

            assertThat(vaultService.findById(vault.getId()).get().getPath())
                    .isEqualTo(TestUtil.absolute(relative));
        }

        // ── marker claim/release wiring ──────────────────────────────────────

        @Test
        @DisplayName("claims the new path's marker and releases the old one when the path actually changes")
        void update_pathChange_claimsNewAndReleasesOld() throws Exception {
            Path oldContent = newVaultDir("update-claim-old");
            Path newContent = newVaultDir("update-claim-new");
            Vault vault = vaultService.create("Alice", "update-claim", oldContent.toString());

            Vault moved = new Vault(vault.getId(), vault.getOwner(), vault.getName(), newContent.toString());
            vaultService.update(moved);

            assertThat(Files.exists(oldContent.resolve(".vault"))).isFalse();
            VaultMarker newMarker = JsonMapper.loadVaultMarkerFromFile(newContent.resolve(".vault").toFile());
            assertThat(newMarker).isNotNull();
            assertThat(newMarker.id()).isEqualTo(vault.getId());
        }

        @Test
        @DisplayName("does not touch any marker when the path is unchanged")
        void update_noPathChange_doesNotTouchMarkers() throws Exception {
            Path content = newVaultDir("update-no-claim");
            Vault vault = vaultService.create("Alice", "update-no-claim", content.toString());
            VaultMarker beforeMarker = JsonMapper.loadVaultMarkerFromFile(content.resolve(".vault").toFile());

            Vault renamed = new Vault(vault.getId(), vault.getOwner(), "update-no-claim-renamed", vault.getPath());
            vaultService.update(renamed);

            VaultMarker afterMarker = JsonMapper.loadVaultMarkerFromFile(content.resolve(".vault").toFile());
            assertThat(afterMarker).isEqualTo(beforeMarker);
        }
    }

    // ── delete() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        void delete_existingVault_removesAndPersists() throws Exception {
            Path content = newVaultDir("delete-me");
            Vault vault = vaultService.create("Alice", "delete-me", content.toString());

            vaultService.delete(vault.getId());

            assertThat(vaultService.findById(vault.getId())).isNotPresent();
            List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.vaultFile);
            assertThat(onDisk.stream().noneMatch(v -> v.getName().equals("delete-me"))).isTrue();
        }

        @Test
        void delete_nullId_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> vaultService.delete(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void delete_thenCreateWithSameRepoSlug_doesNotThrow() throws Exception {
            Vault vault = vaultService.create("Alice", "reusable-slug", newVaultDir("reusable-slug-a").toString());
            vaultService.delete(vault.getId());

            Vault recreated = vaultService.create("Alice", "reusable-slug", newVaultDir("reusable-slug-b").toString());

            assertThat(vaultService.findByRepoSlug("Alice/reusable-slug")).isPresent();
            assertThat(vaultService.findByRepoSlug("Alice/reusable-slug").get().getId())
                    .isEqualTo(recreated.getId());
        }

        @Test
        @DisplayName("frees the path for reuse — a deleted vault's directory can be reclaimed by a new vault")
        void delete_thenCreateWithSamePath_doesNotThrow() throws Exception {
            Path content = newVaultDir("reusable-path");
            Vault vault = vaultService.create("Alice", "reusable-path-a", content.toString());
            vaultService.delete(vault.getId());

            Vault recreated = vaultService.create("Alice", "reusable-path-b", content.toString());

            assertThat(vaultService.findById(recreated.getId())).isPresent();
        }

        @Test
        @DisplayName("releases the vault's .vault marker, but leaves the physical directory untouched")
        void delete_releasesMarkerButNotDirectory() throws Exception {
            Path content = newVaultDir("delete-releases-marker");
            Vault vault = vaultService.create("Alice", "delete-releases-marker", content.toString());
            assertThat(Files.exists(content.resolve(".vault"))).isTrue();

            vaultService.delete(vault.getId());

            assertThat(Files.exists(content.resolve(".vault"))).isFalse();
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
        void findAll_returnsDefensiveCopy() throws Exception {
            vaultService.create("Alice", "defensive-copy-a", newVaultDir("defensive-a").toString());
            vaultService.create("Alice", "defensive-copy-b", newVaultDir("defensive-b").toString());

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
        void findById_existingId_returnsVault() throws Exception {
            Vault vault = vaultService.create("Alice", "findById-target", newVaultDir("findbyid").toString());

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
        void findByRepoSlug_existingSlug_returnsVault() throws Exception {
            vaultService.create("Alice", "slug-target", newVaultDir("slug-target").toString());

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
        void findByRepoSlug_partialMatch_returnsEmpty() throws Exception {
            vaultService.create("Alice", "partial-slug", newVaultDir("partial-slug").toString());

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
        void findAllByName_noMatch_returnsEmptyList() throws Exception {
            vaultService.create("Alice", "findall-existing", newVaultDir("findall-existing").toString());

            assertThat(vaultService.findAllByName("no-such-name")).asList().isEmpty();
        }

        @Test
        void findAllByName_singleMatch_returnsOneElement() throws Exception {
            vaultService.create("Alice", "findall-single", newVaultDir("findall-single").toString());

            List<Vault> result = vaultService.findAllByName("findall-single");

            assertThat(result.size()).isEqualTo(1);
            assertThat(result.get(0).getOwner()).isEqualTo("Alice");
        }

        @Test
        void findAllByName_multipleOwnersSameName_returnsAll() throws Exception {
            vaultService.create("Alice", "findall-ambiguous", newVaultDir("findall-ambig-a").toString());
            vaultService.create("Bob",  "findall-ambiguous", newVaultDir("findall-ambig-b").toString());

            List<Vault> result = vaultService.findAllByName("findall-ambiguous");

            assertThat(result.size()).isEqualTo(2);
            assertThat(result.stream().anyMatch(v -> v.getOwner().equals("Alice"))).isTrue();
            assertThat(result.stream().anyMatch(v -> v.getOwner().equals("Bob"))).isTrue();
        }

        @Test
        void findAllByName_returnsDefensiveCopy() throws Exception {
            vaultService.create("Alice", "findall-defensive", newVaultDir("findall-defensive").toString());

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
        void makeVaultSnapshot_repoSlugWithSlash_producesFlatFolderName() throws Exception {
            Path vaultContent = newVaultDir("snapshot-naming");
            Vault vaultA = vaultService.create("Alice", "snapshot-naming", vaultContent.toString());

            vaultService.makeVaultSnapshot(vaultA);

            Path backupsRoot = testVault.vaultPath().resolve("backups");
            try (var stream = Files.list(backupsRoot)) {
                assertThat(stream.anyMatch(p -> p.getFileName().toString()
                        .startsWith("Alice_snapshot-naming_"))).isTrue();
            }
            assertThat(Files.isDirectory(backupsRoot.resolve("Alice"))).isFalse();
        }

        @Test
        @DisplayName("two vaults with the same name but different owners produce distinct "
                + "snapshot prefixes — the exact collision the repoSlug-based prefix prevents")
        void makeVaultSnapshot_sameNameDifferentOwners_doesNotCollide() throws Exception {
            Path aliceContent = newVaultDir("snapshot-alice");
            Path bobContent   = newVaultDir("snapshot-bob");
            Vault vaultAlice = vaultService.create("Alice", "shared-vault-name", aliceContent.toString());
            Vault vaultBob   = vaultService.create("Bob", "shared-vault-name", bobContent.toString());

            vaultService.makeVaultSnapshot(vaultAlice);
            vaultService.makeVaultSnapshot(vaultBob);

            Path backupsRoot = testVault.vaultPath().resolve("backups");
            try (var stream = Files.list(backupsRoot)) {
                List<String> names = stream.map(p -> p.getFileName().toString()).toList();
                assertThat(names.stream().anyMatch(n -> n.startsWith("Alice_shared-vault-name_"))).isTrue();
                assertThat(names.stream().anyMatch(n -> n.startsWith("Bob_shared-vault-name_"))).isTrue();
            }
        }
    }
}