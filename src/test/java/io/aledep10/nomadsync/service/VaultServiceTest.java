package io.aledep10.nomadsync.service;

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
 * <p>Each test class instance uses a shared {@link TestVault} created in
 * {@code @BeforeAll}. The vault directory is cleaned in {@code @AfterEach}
 * to guarantee filesystem isolation between tests.</p>
 *
 * <p>{@link LogService} is shared across all tests — it carries no mutable
 * test state. {@link VaultService} is recreated in {@code @BeforeEach} with
 * a uniquely-named {@code vaults_<timestamp>.json} file to prevent cross-test
 * contamination on disk.</p>
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
    void load_existingFile_returnsVaults() throws IOException {
        List<Vault> seed = List.of(
                new Vault(UUID.randomUUID().toString(), "vault-1", "/path/1"),
                new Vault(UUID.randomUUID().toString(), "vault-2", "/path/2"));
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
    void load_missingFile_returnsEmptyList() throws IOException {
        vaultService.load();

        assertThat(vaultService.findAll().size()).isEqualTo(0);
    }

    /**
     * Verifies that {@code load()} replaces the current in-memory state — previously
     * created vaults are discarded and only the disk state survives.
     */
    @Test
    void load_replacesExistingInMemoryState() throws IOException {
        vaultService.create("old-vault", "/old/path");
        assertThat(vaultService.findAll().size()).isEqualTo(1);

        List<Vault> diskState = List.of(
                new Vault(UUID.randomUUID().toString(), "new-vault-1", "/new/path/1"),
                new Vault(UUID.randomUUID().toString(), "new-vault-2", "/new/path/2"));
        JsonMapper.saveVaultsToFile(vaultService.vaultFile, diskState);

        vaultService.load();

        assertThat(vaultService.findAll().size()).isEqualTo(2);
        assertThat(vaultService.findByName("old-vault")).isNotPresent();
        assertThat(vaultService.findByName("new-vault-1")).isPresent();
        assertThat(vaultService.findByName("new-vault-2")).isPresent();
    }

    // ── create() ──────────────────────────────────────────────────────────────

    /**
     * Verifies that {@code create()} adds the vault to the in-memory map and
     * immediately persists it — the file must exist after the call.
     */
    @Test
    void create_addsVaultAndPersists() throws IOException {
        Vault vault = vaultService.create("vault-create", "/some/path");

        assertThat(vaultService.findAll().size()).isEqualTo(1);
        assertThat(vaultService.findById(vault.getId())).isPresent();
        assertThat(vaultService.vaultFile.exists()).isTrue();
    }

    /**
     * Verifies that two {@code create()} calls for vaults with the same name produce
     * two distinct entries — IDs are generated independently via {@link UUID#randomUUID()}.
     */
    @Test
    void create_generatesUniqueIds() throws IOException {
        vaultService.create("duplicate-name", "/path/a");
        vaultService.create("duplicate-name", "/path/b");

        List<Vault> all = vaultService.findAll();
        assertThat(all.size()).isEqualTo(2);
        assertThat(all.get(0).getId()).isNotEqualTo(all.get(1).getId());
    }

    /**
     * Verifies that the vault file written by {@code create()} can be read back by
     * {@link JsonMapper} and contains the created vault.
     */
    @Test
    void create_persistsToFile_vaultFileContainsCreatedVault() throws IOException {
        vaultService.create("persisted-vault", "/persisted/path");

        List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.vaultFile);
        assertThat(onDisk.stream().anyMatch(v -> v.getName().equals("persisted-vault"))).isTrue();
    }

    // ── update() ──────────────────────────────────────────────────────────────

    /**
     * Verifies that {@code update()} replaces the in-memory entry and persists — the
     * old name disappears and the new name is present.
     */
    @Test
    void update_existingVault_persistsChanges() throws IOException {
        Vault vault = vaultService.create("original-name", "/some/path");
        vault.setName("updated-name");

        vaultService.update(vault);

        assertThat(vaultService.findByName("updated-name")).isPresent();
        assertThat(vaultService.findByName("original-name")).isNotPresent();
    }

    /**
     * Verifies that {@code update()} throws {@link IllegalArgumentException}
     * when the vault id is {@code null}.
     */
    @Test
    void update_nullId_throwsIllegalArgumentException() {
        Vault vault = new Vault(null, "no-id", "/some/path");

        assertThatThrownBy(() -> vaultService.update(vault))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── delete() ──────────────────────────────────────────────────────────────

    /**
     * Verifies that {@code delete()} removes the vault from memory and from the
     * persisted file.
     */
    @Test
    void delete_existingVault_removesAndPersists() throws IOException {
        Vault vault = vaultService.create("to-delete", "/delete/path");

        vaultService.delete(vault.getId());

        assertThat(vaultService.findById(vault.getId())).isNotPresent();
        List<Vault> onDisk = JsonMapper.loadVaultsFromFile(vaultService.vaultFile);
        assertThat(onDisk.stream().noneMatch(v -> v.getName().equals("to-delete"))).isTrue();
    }

    /**
     * Verifies that {@code delete(null)} throws {@link IllegalArgumentException}.
     */
    @Test
    void delete_nullId_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> vaultService.delete(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── findAll() ─────────────────────────────────────────────────────────────

    /**
     * Verifies that a freshly constructed service returns an empty list.
     */
    @Test
    void findAll_emptyService_returnsEmptyList() {
        assertThat(vaultService.findAll().size()).isEqualTo(0);
    }

    /**
     * Verifies that {@code findAll()} returns a defensive copy — structural
     * modifications to the returned list do not affect the internal map.
     */
    @Test
    void findAll_returnsDefensiveCopy() throws IOException {
        vaultService.create("vault-a", "/path/a");
        vaultService.create("vault-b", "/path/b");

        List<Vault> copy = vaultService.findAll();
        copy.clear();

        assertThat(vaultService.findAll().size()).isEqualTo(2);
    }

    // ── findById() / findByName() ─────────────────────────────────────────────

    /**
     * Verifies that {@code findById()} returns the vault for a known id.
     */
    @Test
    void findById_existingId_returnsVault() throws IOException {
        Vault vault = vaultService.create("findme-by-id", "/path/findme");

        Optional<Vault> found = vaultService.findById(vault.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("findme-by-id");
    }

    /**
     * Verifies that {@code findById()} returns empty for an unknown id.
     */
    @Test
    void findById_unknownId_returnsEmpty() {
        assertThat(vaultService.findById("non-existent-id")).isNotPresent();
    }

    /**
     * Verifies that {@code findByName()} returns the vault for a known name.
     */
    @Test
    void findByName_existingName_returnsVault() throws IOException {
        Vault vault = vaultService.create("findme-by-name", "/path/findme");

        Optional<Vault> found = vaultService.findByName("findme-by-name");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(vault.getId());
    }
}