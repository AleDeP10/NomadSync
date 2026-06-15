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
 * <h2>Vault name uniqueness (DTR-046)</h2>
 * <p>{@code create()}, {@code update()} and {@code load()} all reject duplicate
 * {@code name} values with {@link VaultException}. Every test method therefore
 * declares {@code throws VaultException} alongside {@code IOException}, and uses
 * distinct names across different vaults within the same test.</p>
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
     *
     * <p>Uses {@code new Vault(...)} + {@code saveVaultsToFile} to simulate a file
     * written by a previous session — the goal is to test {@code load()}, not
     * {@code create()}.</p>
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
     * Verifies that {@code load()} on a non-existent file leaves the service empty
     * without throwing.
     */
    @Test
    void load_missingFile_returnsEmptyList() throws IOException, VaultException {
        vaultService.load();

        assertThat(vaultService.findAll().size()).isEqualTo(0);
    }

    /**
     * Verifies that {@code load()} replaces the current in-memory state — previously
     * created vaults are discarded and only the disk state survives.
     */
    @Test
    void load_replacesExistingInMemoryState() throws IOException, VaultException {
        vaultService.create("AleDeP10", "old-vault", "/old/path");
        assertThat(vaultService.findAll().size()).isEqualTo(1);

        List<Vault> diskState = List.of(
                new Vault(UUID.randomUUID().toString(), "AleDeP10", "new-vault-1", "/new/path/1"),
                new Vault(UUID.randomUUID().toString(), "AleDeP10", "new-vault-2", "/new/path/2"));
        JsonMapper.saveVaultsToFile(vaultService.vaultFile, diskState);

        vaultService.load();

        assertThat(vaultService.findAll().size()).isEqualTo(2);
        assertThat(vaultService.findByName("old-vault")).isNotPresent();
        assertThat(vaultService.findByName("new-vault-1")).isPresent();
        assertThat(vaultService.findByName("new-vault-2")).isPresent();
    }

    /**
     * Verifies that {@code load()} rejects a {@code vaults.json} file containing
     * two vaults with the same {@code name} — even under different {@code owner}s.
     */
    @Test
    void load_duplicateNamesInFile_throwsVaultException() throws IOException {
        List<Vault> diskState = List.of(
                new Vault(UUID.randomUUID().toString(), "AleDeP10", "shared-name", "/path/1"),
                new Vault(UUID.randomUUID().toString(), "belmani-apex", "shared-name", "/path/2"));
        JsonMapper.saveVaultsToFile(vaultService.vaultFile, diskState);

        assertThatThrownBy(() -> vaultService.load())
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("shared-name");
    }

    // ── create() ──────────────────────────────────────────────────────────────

    @Test
    void create_addsVaultAndPersists() throws IOException, VaultException {
        Vault vault = vaultService.create("AleDeP10", "vault-create", "/some/path");

        assertThat(vaultService.findAll().size()).isEqualTo(1);
        assertThat(vaultService.findById(vault.getId())).isPresent();
        assertThat(vaultService.vaultFile.exists()).isTrue();
    }

    /**
     * Verifies that two {@code create()} calls with distinct names produce
     * two distinct entries with distinct generated UUIDs.
     */
    @Test
    void create_generatesUniqueIds() throws IOException, VaultException {
        vaultService.create("AleDeP10", "unique-ids-vault-a", "/path/a");
        vaultService.create("AleDeP10", "unique-ids-vault-b", "/path/b");

        List<Vault> all = vaultService.findAll();
        assertThat(all.size()).isEqualTo(2);
        assertThat(all.get(0).getId()).isNotEqualTo(all.get(1).getId());
    }

    @Test
    void create_persistsToFile_vaultFileContainsCreatedVault() throws IOException, VaultException {
        vaultService.create("AleDeP10", "persisted-vault", "/persisted/path");

        List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.vaultFile);
        assertThat(onDisk.stream().anyMatch(v -> v.getName().equals("persisted-vault"))).isTrue();
    }

    /**
     * Verifies that {@code create()} rejects a name already used by another vault,
     * and does not register or persist the rejected vault.
     */
    @Test
    void create_duplicateName_throwsVaultExceptionAndDoesNotPersist() throws IOException, VaultException {
        vaultService.create("AleDeP10", "duplicate-name", "/path/a");

        assertThatThrownBy(() -> vaultService.create("AleDeP10", "duplicate-name", "/path/b"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("duplicate-name");

        assertThat(vaultService.findAll().size()).isEqualTo(1);
        List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.vaultFile);
        assertThat(onDisk.size()).isEqualTo(1);
    }

    /**
     * Verifies that {@code create()} allows the same name across different
     * {@code owner}s only when no other vault currently uses that name —
     * i.e. duplicate detection is on {@code name} alone, not {@code owner+name}.
     * This test documents the constraint explicitly: a second owner with the
     * same name is rejected.
     */
    @Test
    void create_sameNameDifferentOwner_throwsVaultException() throws IOException, VaultException {
        vaultService.create("AleDeP10", "shared-name", "/path/a");

        assertThatThrownBy(() -> vaultService.create("belmani-apex", "shared-name", "/path/b"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("shared-name");
    }

    // ── update() ──────────────────────────────────────────────────────────────

    @Test
    void update_existingVault_persistsChanges() throws IOException, VaultException {
        Vault vault = vaultService.create("AleDeP10", "original-name", "/some/path");
        vault.setName("updated-name");

        vaultService.update(vault);

        assertThat(vaultService.findByName("updated-name")).isPresent();
        assertThat(vaultService.findByName("original-name")).isNotPresent();
    }

    @Test
    void update_nullId_throwsIllegalArgumentException() {
        Vault vault = new Vault(null, "AleDeP10", "no-id", "/some/path");

        assertThatThrownBy(() -> vaultService.update(vault))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies that {@code update()} allows renaming a vault to its own current
     * name — a no-op rename must not be rejected as a duplicate of itself.
     */
    @Test
    void update_renameToOwnCurrentName_doesNotThrow() throws IOException, VaultException {
        Vault vault = vaultService.create("AleDeP10", "same-name", "/some/path");
        vault.setPath("/some/other/path"); // unrelated change, name unchanged

        vaultService.update(vault);

        assertThat(vaultService.findByName("same-name")).isPresent();
        assertThat(vaultService.findByName("same-name").get().getPath())
                .isEqualTo("/some/other/path");
    }

    /**
     * Verifies that {@code update()} rejects renaming a vault to a name already
     * used by a <em>different</em> vault, and does not persist the rejected change.
     */
    @Test
    void update_renameToAnotherVaultsName_throwsVaultExceptionAndDoesNotPersist()
            throws IOException, VaultException {
        Vault vaultA = vaultService.create("AleDeP10", "rename-collision-vault-a", "/path/a");
        vaultService.create("AleDeP10", "rename-collision-vault-b", "/path/b");

        // Mutate a COPY, not the live in-memory instance — vaultA is the same
        // object reference stored in the service's internal map. Renaming it
        // directly would corrupt the map's state (two entries reporting the
        // same name) before update() is even called, making findByName()
        // results depend on HashMap iteration order.
        Vault renamed = new Vault(vaultA.getId(), vaultA.getOwner(),
                "rename-collision-vault-b", vaultA.getPath());

        assertThatThrownBy(() -> vaultService.update(renamed))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("rename-collision-vault-b");

        // in-memory state for vaultA's name is unchanged on disk
        List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.vaultFile);
        assertThat(onDisk.stream().anyMatch(v -> v.getId().equals(vaultA.getId())
                && v.getName().equals("rename-collision-vault-a"))).isTrue();
    }

    // ── delete() ──────────────────────────────────────────────────────────────

    @Test
    void delete_existingVault_removesAndPersists() throws IOException, VaultException {
        Vault vault = vaultService.create("AleDeP10", "to-delete", "/delete/path");

        vaultService.delete(vault.getId());

        assertThat(vaultService.findById(vault.getId())).isNotPresent();
        List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.vaultFile);
        assertThat(onDisk.stream().noneMatch(v -> v.getName().equals("to-delete"))).isTrue();
    }

    @Test
    void delete_nullId_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> vaultService.delete(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies that after {@code delete()}, the freed name can be reused by
     * {@code create()} without triggering a duplicate-name rejection.
     */
    @Test
    void delete_thenCreateWithSameName_doesNotThrow() throws IOException, VaultException {
        Vault vault = vaultService.create("AleDeP10", "reusable-name", "/path/a");
        vaultService.delete(vault.getId());

        Vault recreated = vaultService.create("AleDeP10", "reusable-name", "/path/b");

        assertThat(vaultService.findByName("reusable-name")).isPresent();
        assertThat(vaultService.findByName("reusable-name").get().getId())
                .isEqualTo(recreated.getId());
    }

    // ── findAll() ─────────────────────────────────────────────────────────────

    @Test
    void findAll_emptyService_returnsEmptyList() {
        assertThat(vaultService.findAll().size()).isEqualTo(0);
    }

    @Test
    void findAll_returnsDefensiveCopy() throws IOException, VaultException {
        vaultService.create("AleDeP10", "defensive-copy-vault-a", "/path/a");
        vaultService.create("AleDeP10", "defensive-copy-vault-b", "/path/b");

        List<Vault> copy = vaultService.findAll();
        copy.clear();

        assertThat(vaultService.findAll().size()).isEqualTo(2);
    }

    // ── findById() / findByName() ─────────────────────────────────────────────

    @Test
    void findById_existingId_returnsVault() throws IOException, VaultException {
        Vault vault = vaultService.create("AleDeP10", "findme-by-id", "/path/findme");

        Optional<Vault> found = vaultService.findById(vault.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("findme-by-id");
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        assertThat(vaultService.findById("non-existent-id")).isNotPresent();
    }

    @Test
    void findByName_existingName_returnsVault() throws IOException, VaultException {
        Vault vault = vaultService.create("AleDeP10", "findme-by-name", "/path/findme");

        Optional<Vault> found = vaultService.findByName("findme-by-name");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(vault.getId());
    }
}