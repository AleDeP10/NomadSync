package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.exception.VaultException;
import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.orchestrator.Vault;
import io.aledep10.nomadsync.util.JsonMapper;
import io.aledep10.nomadsync.util.TestUtil;
import io.aledep10.nomadsync.util.TestVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/**
 * Unit tests for {@link VaultService}.
 *
 * <h2>Two vault creation patterns — why both?</h2>
 * <p>{@code vaultService.create(owner, name, path)} tests the service end-to-end:
 * the service generates the UUID, persists, and returns the domain object.
 * This is the normal case for CRUD and query tests.</p>
 *
 * <p>{@code new Vault(UUID, owner, name, path)} + {@link JsonMapper#saveVaultsToFile}
 * is used only in {@code load()} tests where the goal is to verify that the service
 * correctly reads a file written by a previous session. Using {@code create()} there
 * would bypass the very logic under test.</p>
 *
 * <h2>Identity constraints (DTR-046 corrected, GRM M7 Sprint A)</h2>
 * <p>The unique identifier is {@code repoSlug} ({@code <owner>/<name>}), not
 * {@code name} alone. Two vaults with the same {@code name} but different
 * {@code owner}s are explicitly permitted. Additionally, no two vaults may share
 * the same local {@code path}.</p>
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

    @BeforeAll
    static void prepareSharedState() throws IOException {
        testVault  = TestUtil.getTestVault("VaultServiceTest");
        logService = new LogService(TestUtil.forLogService(testVault, LogLevel.DEBUG));
    }

    @BeforeEach
    void setUp() throws IOException {
        gitignoreService = new GitignoreService(logService);
        Properties properties = TestUtil.forVaultService(testVault);
        vaultService = new VaultService(properties, gitignoreService, logService);
    }

    @AfterEach
    void tearDown() throws IOException {
        TestUtil.cleanup(testVault);
    }

    // ── load() ────────────────────────────────────────────────────────────────

    /**
     * Verifies that {@code load()} reads vaults written by {@link JsonMapper} and
     * makes them available via {@code findById()}.
     */
    @Test
    void load_existingFile_returnsVaults() throws IOException, VaultException {
        List<Vault> seed = List.of(
                new Vault(UUID.randomUUID().toString(), "AleDeP10", "vault-1", "/path/1"),
                new Vault(UUID.randomUUID().toString(), "AleDeP10", "vault-2", "/path/2"));
        JsonMapper.saveVaultsToFile(vaultService.vaultFile, seed);

        vaultService.load();

        for (Vault expected : seed) {
            Optional<Vault> found = vaultService.findById(expected.getId());
            assertThat(found).isPresent();
            assertThat(found.get().getName()).isEqualTo(expected.getName());
            assertThat(found.get().getPath()).isEqualTo(expected.getPath());
        }
    }

    /**
     * Verifies that {@code load()} on a non-existent file leaves the service empty.
     */
    @Test
    void load_missingFile_returnsEmptyList() throws IOException, VaultException {
        vaultService.load();
        assertThat(vaultService.findAll().size()).isEqualTo(0);
    }

    /**
     * Verifies that {@code load()} replaces the current in-memory state entirely.
     */
    @Test
    void load_replacesExistingInMemoryState() throws IOException, VaultException {
        vaultService.create("AleDeP10", "load-replace-old", "/old/path");
        assertThat(vaultService.findAll().size()).isEqualTo(1);

        List<Vault> diskState = List.of(
                new Vault(UUID.randomUUID().toString(), "AleDeP10", "load-replace-new-1", "/new/path/1"),
                new Vault(UUID.randomUUID().toString(), "AleDeP10", "load-replace-new-2", "/new/path/2"));
        JsonMapper.saveVaultsToFile(vaultService.vaultFile, diskState);

        vaultService.load();

        assertThat(vaultService.findAll().size()).isEqualTo(2);
        assertThat(vaultService.findAllByName("load-replace-old")).asList().isEmpty();
        assertThat(vaultService.findAllByName("load-replace-new-1")).asList().hasSize(1);
    }

    /**
     * Verifies that {@code load()} rejects a file containing two vaults with the same
     * {@code repoSlug} (same {@code owner} AND {@code name}).
     */
    @Test
    void load_duplicateRepoSlugsInFile_throwsVaultException() throws IOException {
        List<Vault> diskState = List.of(
                new Vault(UUID.randomUUID().toString(), "AleDeP10", "dup-slug", "/path/a"),
                new Vault(UUID.randomUUID().toString(), "AleDeP10", "dup-slug", "/path/b"));
        JsonMapper.saveVaultsToFile(vaultService.vaultFile, diskState);

        assertThatThrownBy(() -> vaultService.load())
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("AleDeP10/dup-slug");
    }

    /**
     * Verifies that two vaults with the same {@code name} but different {@code owner}s
     * are accepted — {@code name} alone is not a uniqueness constraint.
     */
    @Test
    void load_sameNameDifferentOwners_doesNotThrow() throws IOException, VaultException {
        List<Vault> diskState = List.of(
                new Vault(UUID.randomUUID().toString(), "AleDeP10", "shared-name", "/path/a"),
                new Vault(UUID.randomUUID().toString(), "Belmani",  "shared-name", "/path/b"));
        JsonMapper.saveVaultsToFile(vaultService.vaultFile, diskState);

        vaultService.load();

        assertThat(vaultService.findAll().size()).isEqualTo(2);
    }

    /**
     * Verifies that {@code load()} rejects a file containing two vaults pointing to
     * the same local {@code path}.
     */
    @Test
    void load_duplicatePathsInFile_throwsVaultException() throws IOException {
        List<Vault> diskState = List.of(
                new Vault(UUID.randomUUID().toString(), "AleDeP10", "vault-a", "/shared/path"),
                new Vault(UUID.randomUUID().toString(), "Belmani",  "vault-b", "/shared/path"));
        JsonMapper.saveVaultsToFile(vaultService.vaultFile, diskState);

        assertThatThrownBy(() -> vaultService.load())
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("/shared/path");
    }

    // ── create() ──────────────────────────────────────────────────────────────

    @Test
    void create_addsVaultAndPersists() throws IOException, VaultException {
        Vault vault = vaultService.create("AleDeP10", "create-basic", "/some/path");

        assertThat(vaultService.findAll().size()).isEqualTo(1);
        assertThat(vaultService.findById(vault.getId())).isPresent();
        assertThat(vaultService.vaultFile.exists()).isTrue();
    }

    /**
     * Verifies that two {@code create()} calls with distinct repoSlugs produce
     * two distinct entries with distinct generated UUIDs.
     */
    @Test
    void create_generatesUniqueIds() throws IOException, VaultException {
        vaultService.create("AleDeP10", "unique-ids-a", "/path/a");
        vaultService.create("AleDeP10", "unique-ids-b", "/path/b");

        List<Vault> all = vaultService.findAll();
        assertThat(all.size()).isEqualTo(2);
        assertThat(all.get(0).getId()).isNotEqualTo(all.get(1).getId());
    }

    @Test
    void create_persistsToFile() throws IOException, VaultException {
        vaultService.create("AleDeP10", "create-persist", "/persisted/path");

        List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.vaultFile);
        assertThat(onDisk.stream().anyMatch(v -> v.getName().equals("create-persist"))).isTrue();
    }

    /**
     * Verifies that {@code create()} rejects a duplicate {@code repoSlug}
     * (same {@code owner} AND {@code name}).
     */
    @Test
    void create_duplicateRepoSlug_throwsVaultExceptionAndDoesNotPersist()
            throws IOException, VaultException {
        vaultService.create("AleDeP10", "dup-repoSlug", "/path/a");

        assertThatThrownBy(() -> vaultService.create("AleDeP10", "dup-repoSlug", "/path/b"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("AleDeP10/dup-repoSlug");

        assertThat(vaultService.findAll().size()).isEqualTo(1);
        List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.vaultFile);
        assertThat(onDisk.size()).isEqualTo(1);
    }

    /**
     * Verifies that two vaults with the same {@code name} but different {@code owner}s
     * are accepted by {@code create()}.
     */
    @Test
    void create_sameNameDifferentOwners_doesNotThrow() throws IOException, VaultException {
        vaultService.create("AleDeP10", "shared-name", "/path/a");
        vaultService.create("Belmani",  "shared-name", "/path/b");

        assertThat(vaultService.findAll().size()).isEqualTo(2);
        assertThat(vaultService.findByRepoSlug("AleDeP10/shared-name")).isPresent();
        assertThat(vaultService.findByRepoSlug("Belmani/shared-name")).isPresent();
    }

    /**
     * Verifies that {@code create()} rejects a duplicate local {@code path},
     * even when {@code repoSlug} would be unique.
     */
    @Test
    void create_duplicatePath_throwsVaultExceptionAndDoesNotPersist()
            throws IOException, VaultException {
        vaultService.create("AleDeP10", "dup-path-a", "/shared/path");

        assertThatThrownBy(() -> vaultService.create("Belmani", "dup-path-b", "/shared/path"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("/shared/path");

        assertThat(vaultService.findAll().size()).isEqualTo(1);
    }

    // ── update() ──────────────────────────────────────────────────────────────

    @Test
    void update_existingVault_persistsChanges() throws IOException, VaultException {
        Vault vault = vaultService.create("AleDeP10", "update-original", "/some/path");
        Vault renamed = new Vault(vault.getId(), vault.getOwner(), "update-renamed", vault.getPath());

        vaultService.update(renamed);

        assertThat(vaultService.findByRepoSlug("AleDeP10/update-renamed")).isPresent();
        assertThat(vaultService.findByRepoSlug("AleDeP10/update-original")).isNotPresent();
    }

    @Test
    void update_nullId_throwsIllegalArgumentException() {
        Vault vault = new Vault(null, "AleDeP10", "no-id", "/some/path");
        assertThatThrownBy(() -> vaultService.update(vault))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies that updating a vault with its own current {@code repoSlug}
     * (no-op rename) does not throw.
     */
    @Test
    void update_sameRepoSlug_doesNotThrow() throws IOException, VaultException {
        Vault vault = vaultService.create("AleDeP10", "update-noop", "/some/path");
        Vault withNewPath = new Vault(vault.getId(), vault.getOwner(),
                vault.getName(), "/some/other/path");

        vaultService.update(withNewPath);

        assertThat(vaultService.findByRepoSlug("AleDeP10/update-noop")).isPresent();
        assertThat(vaultService.findByRepoSlug("AleDeP10/update-noop").get().getPath())
                .isEqualTo("/some/other/path");
    }

    /**
     * Verifies that renaming a vault to a {@code repoSlug} already used by a
     * different vault throws.
     */
    @Test
    void update_renameToExistingRepoSlug_throwsVaultExceptionAndDoesNotPersist()
            throws IOException, VaultException {
        Vault vaultA = vaultService.create("AleDeP10", "update-collision-a", "/path/a");
        vaultService.create("AleDeP10", "update-collision-b", "/path/b");

        // Construct a copy — never mutate the in-memory instance directly
        Vault renamedA = new Vault(vaultA.getId(), vaultA.getOwner(),
                "update-collision-b", vaultA.getPath());

        assertThatThrownBy(() -> vaultService.update(renamedA))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("AleDeP10/update-collision-b");

        List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.vaultFile);
        assertThat(onDisk.stream()
                .anyMatch(v -> v.getId().equals(vaultA.getId())
                        && v.getName().equals("update-collision-a"))).isTrue();
    }

    /**
     * Verifies that changing only the {@code owner} of a vault to create a
     * {@code repoSlug} already used by a different vault throws.
     */
    @Test
    void update_changeOwnerToExistingRepoSlug_throwsVaultException()
            throws IOException, VaultException {
        Vault vaultA = vaultService.create("AleDeP10",  "owner-collision", "/path/a");
        vaultService.create("Belmani", "owner-collision", "/path/b");

        // Attempt to change vaultA's owner to "Belmani" → repoSlug "Belmani/owner-collision" exists
        Vault changedOwner = new Vault(vaultA.getId(), "Belmani",
                vaultA.getName(), vaultA.getPath());

        assertThatThrownBy(() -> vaultService.update(changedOwner))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("Belmani/owner-collision");
    }

    /**
     * Verifies that updating a vault to a {@code path} already used by a
     * different vault throws.
     */
    @Test
    void update_duplicatePath_throwsVaultException() throws IOException, VaultException {
        Vault vaultA = vaultService.create("AleDeP10", "path-collision-a", "/path/a");
        vaultService.create("AleDeP10", "path-collision-b", "/path/b");

        Vault movedA = new Vault(vaultA.getId(), vaultA.getOwner(),
                vaultA.getName(), "/path/b");

        assertThatThrownBy(() -> vaultService.update(movedA))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("/path/b");
    }

    /**
     * Verifies that updating a vault's {@code path} to its own current path
     * (no-op path update) does not throw.
     */
    @Test
    void update_samePathNoOp_doesNotThrow() throws IOException, VaultException {
        Vault vault = vaultService.create("AleDeP10", "path-noop", "/same/path");
        Vault noOp = new Vault(vault.getId(), vault.getOwner(), "path-noop-renamed", vault.getPath());

        vaultService.update(noOp);

        assertThat(vaultService.findByRepoSlug("AleDeP10/path-noop-renamed")).isPresent();
    }

    // ── delete() ──────────────────────────────────────────────────────────────

    @Test
    void delete_existingVault_removesAndPersists() throws IOException, VaultException {
        Vault vault = vaultService.create("AleDeP10", "delete-me", "/delete/path");

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

    /**
     * Verifies that after {@code delete()}, the freed {@code repoSlug} can be
     * reused by {@code create()}.
     */
    @Test
    void delete_thenCreateWithSameRepoSlug_doesNotThrow() throws IOException, VaultException {
        Vault vault = vaultService.create("AleDeP10", "reusable-slug", "/path/a");
        vaultService.delete(vault.getId());

        Vault recreated = vaultService.create("AleDeP10", "reusable-slug", "/path/b");

        assertThat(vaultService.findByRepoSlug("AleDeP10/reusable-slug")).isPresent();
        assertThat(vaultService.findByRepoSlug("AleDeP10/reusable-slug").get().getId())
                .isEqualTo(recreated.getId());
    }

    /**
     * Verifies that after {@code delete()}, the freed {@code path} can be
     * reused by {@code create()}.
     */
    @Test
    void delete_thenCreateWithSamePath_doesNotThrow() throws IOException, VaultException {
        Vault vault = vaultService.create("AleDeP10", "reusable-path-a", "/reusable/path");
        vaultService.delete(vault.getId());

        Vault recreated = vaultService.create("AleDeP10", "reusable-path-b", "/reusable/path");

        assertThat(vaultService.findById(recreated.getId())).isPresent();
    }

    // ── findAll() ─────────────────────────────────────────────────────────────

    @Test
    void findAll_emptyService_returnsEmptyList() {
        assertThat(vaultService.findAll().size()).isEqualTo(0);
    }

    @Test
    void findAll_returnsDefensiveCopy() throws IOException, VaultException {
        vaultService.create("AleDeP10", "defensive-copy-a", "/path/a");
        vaultService.create("AleDeP10", "defensive-copy-b", "/path/b");

        List<Vault> copy = vaultService.findAll();
        copy.clear();

        assertThat(vaultService.findAll().size()).isEqualTo(2);
    }

    // ── findById() ────────────────────────────────────────────────────────────

    @Test
    void findById_existingId_returnsVault() throws IOException, VaultException {
        Vault vault = vaultService.create("AleDeP10", "findById-target", "/path/findme");

        Optional<Vault> found = vaultService.findById(vault.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("findById-target");
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        assertThat(vaultService.findById("non-existent-id")).isNotPresent();
    }

    // ── findByRepoSlug() ──────────────────────────────────────────────────────

    @Test
    void findByRepoSlug_existingSlug_returnsVault() throws IOException, VaultException {
        vaultService.create("AleDeP10", "slug-target", "/path/slug");

        Optional<Vault> found = vaultService.findByRepoSlug("AleDeP10/slug-target");

        assertThat(found).isPresent();
        assertThat(found.get().getOwner()).isEqualTo("AleDeP10");
        assertThat(found.get().getName()).isEqualTo("slug-target");
    }

    @Test
    void findByRepoSlug_unknownSlug_returnsEmpty() {
        assertThat(vaultService.findByRepoSlug("AleDeP10/non-existent")).isNotPresent();
    }

    /**
     * Verifies that {@code findByRepoSlug} is exact — partial matches on name
     * or owner alone return empty.
     */
    @Test
    void findByRepoSlug_partialMatch_returnsEmpty() throws IOException, VaultException {
        vaultService.create("AleDeP10", "partial-slug", "/path/partial");

        assertThat(vaultService.findByRepoSlug("partial-slug")).isNotPresent();
        assertThat(vaultService.findByRepoSlug("AleDeP10")).isNotPresent();
        assertThat(vaultService.findByRepoSlug("Belmani/partial-slug")).isNotPresent();
    }

    // ── findAllByName() ───────────────────────────────────────────────────────

    @Test
    void findAllByName_noMatch_returnsEmptyList() throws IOException, VaultException {
        vaultService.create("AleDeP10", "findall-existing", "/path/existing");

        assertThat(vaultService.findAllByName("no-such-name")).asList().isEmpty();
    }

    @Test
    void findAllByName_singleMatch_returnsOneElement() throws IOException, VaultException {
        vaultService.create("AleDeP10", "findall-single", "/path/single");

        List<Vault> result = vaultService.findAllByName("findall-single");

        assertThat(result.size()).isEqualTo(1);
        assertThat(result.get(0).getOwner()).isEqualTo("AleDeP10");
    }

    /**
     * Verifies that {@code findAllByName} returns all vaults with the same name
     * across different owners — the primary use case for this method.
     */
    @Test
    void findAllByName_multipleOwnersSameName_returnsAll() throws IOException, VaultException {
        vaultService.create("AleDeP10", "findall-ambiguous", "/path/a");
        vaultService.create("Belmani",  "findall-ambiguous", "/path/b");

        List<Vault> result = vaultService.findAllByName("findall-ambiguous");

        assertThat(result.size()).isEqualTo(2);
        assertThat(result.stream().anyMatch(v -> v.getOwner().equals("AleDeP10"))).isTrue();
        assertThat(result.stream().anyMatch(v -> v.getOwner().equals("Belmani"))).isTrue();
    }

    @Test
    void findAllByName_returnsDefensiveCopy() throws IOException, VaultException {
        vaultService.create("AleDeP10", "findall-defensive", "/path/defensive");

        List<Vault> copy = vaultService.findAllByName("findall-defensive");
        copy.clear();

        assertThat(vaultService.findAllByName("findall-defensive").size()).isEqualTo(1);
    }
}