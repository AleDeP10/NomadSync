package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.config.NomadPropertiesLoader;
import io.aledep10.nomadsync.exception.MarkerClaimException;
import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.marker.*;
import io.aledep10.nomadsync.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;

/**
 * Unit tests for {@link MarkerService}.
 *
 * <p>Uses real {@link VaultMarkerStrategy}/{@link WorkspaceMarkerStrategy}
 * (both already GREEN) registered in the service's strategy map — no mocking
 * of marker behaviour, only the filesystem is real and per-test isolated via
 * {@link TempDirs}.</p>
 *
 * <h2>Coverage strategy</h2>
 * <ul>
 *   <li>{@code claim()} — fresh claim, exact-path conflict, ancestor conflict
 *       (cross-type — a {@code WORKSPACE} marker blocks a {@code VAULT} claim
 *       nested inside it, and vice versa), descendant conflict (bounded,
 *       {@code VAULT}-only), self-reclaim failure.</li>
 *   <li>{@code release()} — removes an existing marker; no-op if absent.</li>
 *   <li>{@code refresh()} — creates fresh if absent, refreshes timestamp
 *       preserving {@code createdAt} if same claimant, does not overwrite if
 *       a different claimant already holds the marker.</li>
 *   <li>{@code checkNoNestingConflict()} — cross-type ancestor scan, the
 *       candidate's own marker never checked, bounded descendant scan
 *       specific to {@code VAULT} (a {@code WORKSPACE} descendant within
 *       depth must NOT trigger a conflict — locks in the asymmetry decided
 *       during grooming).</li>
 * </ul>
 */
@ExtendWith({TempDirCleanupExtension.class, ClassFailureTracker.class})
class MarkerServiceTest {

    static Properties properties;
    static TestVault sharedVault;
    static NomadPropertiesLoader loader;
    static LogService logService;

    MarkerService markerService;

    @BeforeAll
    static void prepareSharedState() throws IOException {
        sharedVault = TestUtil.getTestVault("MarkerServiceTest-shared");
        properties = TestUtil.forLogService(sharedVault, LogLevel.INFO);
        properties.setProperty("marker.maxNestingDepth", "6");
        loader = NomadPropertiesLoader.forTesting(TestUtil.forLogService(sharedVault, LogLevel.DEBUG));
        logService  = new LogService(loader, sharedVault.rootPath());
    }

    @AfterAll
    static void tearDownAll(ExtensionContext context) throws IOException {
        logService.close();
        if (!ClassFailureTracker.anyTestFailed(context)) {
            TestUtil.cleanup(sharedVault);
        }
    }

    @BeforeEach
    void setUp() {
        // Logica superflua, fatta salire alla definizione delle properties in beforeAll
        // Properties properties = new Properties();
        // properties.setProperty("marker.maxNestingDepth", "6");
        markerService = new MarkerService(loader, logService);
    }

    /**
     * Builds a throwaway {@link MarkerService} configured with a non-default
     * {@code marker.maxNestingDepth} — used <strong>only</strong> by
     * {@link CheckNoNestingConflictTests#defaultOverload_usesConfiguredDepth},
     * to prove the no-argument {@code checkNoNestingConflict(String)} overload
     * genuinely reads its default from the constructor rather than coincidentally
     * matching {@code PropertiesUtil.getInt}'s own built-in fallback (6).
     *
     * <p>Every other test in this suite exercises depth-dependent scanning
     * behaviour on the single shared {@link #markerService} via the explicit
     * {@code checkNoNestingConflict(String, int)} overload — never by
     * reconstructing a service. Do not reach for this helper for that purpose;
     * it exists solely to test the constructor's own property-reading wiring.</p>
     */
    private MarkerService markerServiceConfiguredWithNonDefaultDepth(int maxNestingDepth) {
        Properties properties = TestUtil.forLogService(sharedVault, LogLevel.INFO);
        properties.setProperty("marker.maxNestingDepth", String.valueOf(maxNestingDepth));
        return new MarkerService(NomadPropertiesLoader.forTesting(properties), logService);
    }

    // ── claim() ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("claim()")
    class ClaimTests {

        @Test
        @DisplayName("writes a fresh marker when the path is unclaimed and has no nesting conflicts")
        void freshPath_claimsSuccessfully(TempDirs tempDirs) throws Exception {
            Path dir = tempDirs.newDir("MarkerServiceTest", "claim-fresh");
            VaultMarker marker = VaultMarker.create("id-1", "Alice/vault", "/some/catalog.json", "2026-01-01T00:00:00");

            markerService.claim(MarkerType.VAULT, dir.toString(), marker);

            Path descriptor = dir.resolve(MarkerType.VAULT.folderName()).resolve(MarkerType.DESCRIPTOR_FILE_NAME);
            assertThat(Files.exists(descriptor)).isTrue();
            assertThat(Files.readString(descriptor)).contains("Alice/vault");
        }

        @Test
        @DisplayName("throws and does not overwrite when the exact path is already claimed")
        void exactPathAlreadyClaimed_throwsAndDoesNotOverwrite(TempDirs tempDirs) throws Exception {
            Path dir = tempDirs.newDir("MarkerServiceTest", "claim-exact-conflict");
            VaultMarker foreign = VaultMarker.create("foreign-id", "Bob/foreign", "/some/catalog.json", "2020-01-01T00:00:00");
            markerService.claim(MarkerType.VAULT, dir.toString(), foreign);

            VaultMarker mine = VaultMarker.create("id-1", "Alice/vault", "/some/catalog.json", "2026-01-01T00:00:00");

            assertThatThrownBy(() -> markerService.claim(MarkerType.VAULT, dir.toString(), mine))
                    .isInstanceOf(MarkerClaimException.class)
                    .hasMessageContaining("Bob/foreign");

            Path descriptor = dir.resolve(MarkerType.VAULT.folderName()).resolve(MarkerType.DESCRIPTOR_FILE_NAME);
            assertThat(Files.readString(descriptor)).contains("Bob/foreign");
        }

        @Test
        @DisplayName("a second claim on the same already-claimed-by-self path also fails")
        void secondClaimOnOwnPath_alsoFails(TempDirs tempDirs) throws Exception {
            Path dir = tempDirs.newDir("MarkerServiceTest", "claim-twice");
            VaultMarker marker = VaultMarker.create("id-1", "Alice/vault", "/some/catalog.json", "2026-01-01T00:00:00");

            markerService.claim(MarkerType.VAULT, dir.toString(), marker);

            assertThatThrownBy(() -> markerService.claim(MarkerType.VAULT, dir.toString(), marker))
                    .isInstanceOf(MarkerClaimException.class);
        }

        @Test
        @DisplayName("throws when claiming a path nested inside a marker folder of a DIFFERENT type")
        void claimInsideDifferentTypeMarkerFolder_throws(TempDirs tempDirs) throws Exception {
            Path parent = tempDirs.newDir("MarkerServiceTest", "claim-inside-marker-folder");
            WorkspaceMarker workspaceMarker = WorkspaceMarker.create("ws-id", "Alice", "2026-01-01T00:00:00");
            markerService.claim(MarkerType.WORKSPACE, parent.toString(), workspaceMarker);

            Path child = parent.resolve(MarkerType.WORKSPACE.folderName()).resolve("nested-vault");
            Files.createDirectories(child);
            VaultMarker vaultMarker = VaultMarker.create("id-1", "Alice/vault", "/some/catalog.json", "2026-01-01T00:00:00");

            assertThatThrownBy(() -> markerService.claim(MarkerType.VAULT, child.toString(), vaultMarker))
                    .isInstanceOf(MarkerClaimException.class)
                    .hasMessageContaining("Alice");
        }

        @Test
        @DisplayName("claiming a vault as sibling of an already-claimed workspace marker succeeds — the normal case")
        void claimSiblingOfWorkspaceMarker_succeeds(TempDirs tempDirs) throws Exception {
            Path parent = tempDirs.newDir("MarkerServiceTest", "claim-sibling-ok");
            WorkspaceMarker workspaceMarker = WorkspaceMarker.create("ws-id", "Alice", "2026-01-01T00:00:00");
            markerService.claim(MarkerType.WORKSPACE, parent.toString(), workspaceMarker);

            Path child = parent.resolve("nested-vault");
            Files.createDirectories(child);
            VaultMarker vaultMarker = VaultMarker.create("id-1", "Alice/vault", "/some/catalog.json", "2026-01-01T00:00:00");

            assertThatCode(() -> markerService.claim(MarkerType.VAULT, child.toString(), vaultMarker))
                    .doesNotThrowAnyException();
            assertThat(Files.isDirectory(child.resolve(MarkerType.VAULT.folderName()))).isTrue();
        }

        @Test
        @DisplayName("throws when a descendant within depth already carries a VAULT marker")
        void descendantConflict_vault_throws(TempDirs tempDirs) throws Exception {
            Path candidate = tempDirs.newDir("MarkerServiceTest", "claim-descendant-conflict");
            Path nested = candidate.resolve("sub").resolve("deeper");
            Files.createDirectories(nested);
            VaultMarker nestedMarker = VaultMarker.create("nested-id", "Bob/nested", "/some/catalog.json", "2026-01-01T00:00:00");
            markerService.claim(MarkerType.VAULT, nested.toString(), nestedMarker);

            VaultMarker candidateMarker = VaultMarker.create("id-1", "Alice/vault", "/some/catalog.json", "2026-01-01T00:00:00");

            assertThatThrownBy(() -> markerService.claim(MarkerType.VAULT, candidate.toString(), candidateMarker))
                    .isInstanceOf(MarkerClaimException.class)
                    .hasMessageContaining("Bob/nested");
        }
    }

    // ── release() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("release()")
    class ReleaseTests {

        @Test
        @DisplayName("removes an existing marker folder entirely")
        void existingMarker_isRemoved(TempDirs tempDirs) throws Exception {
            Path dir = tempDirs.newDir("MarkerServiceTest", "release-existing");
            VaultMarker marker = VaultMarker.create("id-1", "Alice/vault", "/some/catalog.json", "2026-01-01T00:00:00");
            markerService.claim(MarkerType.VAULT, dir.toString(), marker);

            markerService.release(MarkerType.VAULT, dir.toString());

            assertThat(Files.exists(dir.resolve(MarkerType.VAULT.folderName()))).isFalse();
            assertThat(Files.exists(dir)).isTrue();
        }

        @Test
        @DisplayName("is a no-op when no marker exists at the path")
        void noMarker_isNoOp(TempDirs tempDirs) throws Exception {
            Path dir = tempDirs.newDir("MarkerServiceTest", "release-absent");

            markerService.release(MarkerType.VAULT, dir.toString());
            // no exception = pass
        }
    }

    // ── refresh() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("refresh()")
    class RefreshTests {

        @Test
        @DisplayName("creates a fresh marker when none exists yet")
        void noExistingMarker_createsFresh(TempDirs tempDirs) throws Exception {
            Path dir = tempDirs.newDir("MarkerServiceTest", "refresh-fresh");
            VaultMarker marker = VaultMarker.create("id-1", "Alice/vault", "/some/catalog.json", "2026-01-01T00:00:00");

            markerService.refresh(MarkerType.VAULT, dir.toString(), marker);

            Path descriptor = dir.resolve(MarkerType.VAULT.folderName()).resolve(MarkerType.DESCRIPTOR_FILE_NAME);
            assertThat(Files.readString(descriptor)).contains("Alice/vault");
        }

        @Test
        @DisplayName("refreshes lastUpdate but preserves createdAt when the same claimant confirms again")
        void matchingClaimant_refreshesTimestampPreservesCreatedAt(TempDirs tempDirs) throws Exception {
            Path dir = tempDirs.newDir("MarkerServiceTest", "refresh-matching");
            VaultMarker first = VaultMarker.create("id-1", "Alice/vault", "/some/catalog.json", "2026-01-01T00:00:00");
            markerService.claim(MarkerType.VAULT, dir.toString(), first);

            VaultMarker confirmAgain = VaultMarker.create("id-1", "Alice/vault", "/some/catalog.json", "2026-06-01T00:00:00");
            markerService.refresh(MarkerType.VAULT, dir.toString(), confirmAgain);

            Path descriptor = dir.resolve(MarkerType.VAULT.folderName()).resolve(MarkerType.DESCRIPTOR_FILE_NAME);
            String content = Files.readString(descriptor);
            assertThat(content).contains("2026-01-01T00:00:00"); // createdAt preserved
            assertThat(content).contains("2026-06-01T00:00:00"); // lastUpdate refreshed
        }

        @Test
        @DisplayName("does not overwrite a marker belonging to a different claimant")
        void differentClaimant_doesNotOverwrite(TempDirs tempDirs) throws Exception {
            Path dir = tempDirs.newDir("MarkerServiceTest", "refresh-conflict");
            VaultMarker foreign = VaultMarker.create("foreign-id", "Bob/foreign", "/some/catalog.json", "2020-01-01T00:00:00");
            markerService.claim(MarkerType.VAULT, dir.toString(), foreign);

            VaultMarker mine = VaultMarker.create("id-1", "Alice/vault", "/some/catalog.json", "2026-01-01T00:00:00");
            markerService.refresh(MarkerType.VAULT, dir.toString(), mine);

            Path descriptor = dir.resolve(MarkerType.VAULT.folderName()).resolve(MarkerType.DESCRIPTOR_FILE_NAME);
            assertThat(Files.readString(descriptor)).contains("Bob/foreign");
        }
    }

    @Nested
    @DisplayName("overwrite")
    class OverwriteTests {

        @Test
        @DisplayName("preserves the existing descriptor's createdAt, refreshes lastUpdate")
        void preservesCreatedAt(TempDirs tempDirs) throws Exception {
            Path dir = tempDirs.newDir("MarkerServiceTest", "overwrite-preserves-createdat");
            markerService.claim(MarkerType.WORKSPACE, dir.toString(),
                    WorkspaceMarker.create("ws-id", "smk-a", "2026-01-01T00:00:00"));

            markerService.overwrite(MarkerType.WORKSPACE, dir.toString(),
                    WorkspaceMarker.create("ws-id", "smk-a", "2026-06-15T12:00:00"));

            Path descriptor = dir.resolve(MarkerType.WORKSPACE.folderName())
                    .resolve(MarkerType.DESCRIPTOR_FILE_NAME);
            WorkspaceMarker rewritten = (WorkspaceMarker) new WorkspaceMarkerStrategy()
                    .deserialize(Files.readString(descriptor));

            assertThat(rewritten.createdAt()).isEqualTo("2026-01-01T00:00:00");
            assertThat(rewritten.lastUpdate()).isEqualTo("2026-06-15T12:00:00");
        }

        @Test
        @DisplayName("writes the given marker as-is when the marker folder exists but no descriptor is inside it yet")
        void noExistingDescriptor_writesAsGiven(TempDirs tempDirs) throws Exception {
            Path dir = tempDirs.newDir("MarkerServiceTest", "overwrite-no-existing");
            Files.createDirectories(dir.resolve(MarkerType.WORKSPACE.folderName()));
            WorkspaceMarker fresh = WorkspaceMarker.create("ws-id", "smk-a", "2026-06-15T12:00:00");

            markerService.overwrite(MarkerType.WORKSPACE, dir.toString(), fresh);

            Path descriptor = dir.resolve(MarkerType.WORKSPACE.folderName())
                    .resolve(MarkerType.DESCRIPTOR_FILE_NAME);
            WorkspaceMarker written = (WorkspaceMarker) new WorkspaceMarkerStrategy()
                    .deserialize(Files.readString(descriptor));

            assertThat(written.createdAt()).isEqualTo("2026-06-15T12:00:00");
            assertThat(written.lastUpdate()).isEqualTo("2026-06-15T12:00:00");
        }

        @Test
        @DisplayName("overwrite is idempotent-safe: two consecutive calls both preserve the ORIGINAL createdAt")
        void consecutiveOverwrites_preserveOriginalCreatedAt(TempDirs tempDirs) throws Exception {
            Path dir = tempDirs.newDir("MarkerServiceTest", "overwrite-consecutive");
            markerService.claim(MarkerType.WORKSPACE, dir.toString(),
                    WorkspaceMarker.create("ws-id", "smk-a", "2026-01-01T00:00:00"));

            markerService.overwrite(MarkerType.WORKSPACE, dir.toString(),
                    WorkspaceMarker.create("ws-id", "smk-a", "2026-03-01T00:00:00"));
            markerService.overwrite(MarkerType.WORKSPACE, dir.toString(),
                    WorkspaceMarker.create("ws-id", "smk-a", "2026-06-15T12:00:00"));

            Path descriptor = dir.resolve(MarkerType.WORKSPACE.folderName())
                    .resolve(MarkerType.DESCRIPTOR_FILE_NAME);
            WorkspaceMarker rewritten = (WorkspaceMarker) new WorkspaceMarkerStrategy()
                    .deserialize(Files.readString(descriptor));

            assertThat(rewritten.createdAt()).isEqualTo("2026-01-01T00:00:00");
            assertThat(rewritten.lastUpdate()).isEqualTo("2026-06-15T12:00:00");
        }
    }

    // ── checkNoNestingConflict() ──────────────────────────────────────────────

    @Nested
    @DisplayName("checkNoNestingConflict")
    class CheckNoNestingConflictTests {

        @Test
        @DisplayName("does not throw when no marker exists anywhere near the candidate path")
        void noMarkersAnywhere_doesNotThrow(TempDirs tempDirs) throws Exception {
            Path candidate = tempDirs.newDir("MarkerServiceTest", "nesting-none").resolve("brand-new");

            markerService.checkNoNestingConflict(candidate.toString(), MarkerType.VAULT);
            // no exception = pass
        }

        @Test
        @DisplayName("throws when the candidate path is nested inside a reserved marker folder itself")
        void candidateInsideMarkerFolder_throws(TempDirs tempDirs) throws Exception {
            Path parent = tempDirs.newDir("MarkerServiceTest", "nesting-inside-marker-folder");
            WorkspaceMarker workspaceMarker = WorkspaceMarker.create("ws-id", "Alice", "2026-01-01T00:00:00");
            markerService.claim(MarkerType.WORKSPACE, parent.toString(), workspaceMarker);

            // Degenerate: a candidate whose path runs THROUGH the reserved marker
            // folder itself, not merely a sibling of it.
            Path candidate = parent.resolve(MarkerType.WORKSPACE.folderName()).resolve("child-vault");

            assertThatThrownBy(() -> markerService.checkNoNestingConflict(candidate.toString(), MarkerType.VAULT))
                    .isInstanceOf(MarkerClaimException.class)
                    .hasMessageContaining("Alice");
        }

        @Test
        @DisplayName("does NOT throw for a vault sibling of an already-claimed workspace marker — the normal case")
        void siblingOfWorkspaceMarker_noConflict(TempDirs tempDirs) throws Exception {
            Path parent = tempDirs.newDir("MarkerServiceTest", "nesting-sibling-ok");
            WorkspaceMarker workspaceMarker = WorkspaceMarker.create("ws-id", "Alice", "2026-01-01T00:00:00");
            markerService.claim(MarkerType.WORKSPACE, parent.toString(), workspaceMarker);

            Path candidate = parent.resolve("child-vault");

            assertThatCode(() -> markerService.checkNoNestingConflict(candidate.toString(), MarkerType.VAULT))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("does not check the candidate path itself — only ancestors and descendants")
        void candidateItselfMarked_isNotChecked(TempDirs tempDirs) throws Exception {
            Path candidate = tempDirs.newDir("MarkerServiceTest", "nesting-self");
            VaultMarker marker = VaultMarker.create("id-1", "Alice/vault", "/some/catalog.json", "2026-01-01T00:00:00");
            markerService.claim(MarkerType.VAULT, candidate.toString(), marker);

            markerService.checkNoNestingConflict(candidate.toString(), MarkerType.VAULT);
            // no exception = pass — the candidate's own marker is out of scope here
        }

        @Test
        @DisplayName("throws when a VAULT descendant within depth carries a marker")
        void vaultDescendantWithinDepth_throws(TempDirs tempDirs) throws Exception {
            Path candidate = tempDirs.newDir("MarkerServiceTest", "nesting-descendant-vault");
            Path deepChild = candidate.resolve("level1").resolve("level2");
            Files.createDirectories(deepChild);
            VaultMarker marker = VaultMarker.create("id-1", "Bob/nested", "/some/catalog.json", "2026-01-01T00:00:00");
            markerService.claim(MarkerType.VAULT, deepChild.toString(), marker);

            assertThatThrownBy(() -> markerService.checkNoNestingConflict(candidate.toString(), MarkerType.VAULT, 3))
                    .isInstanceOf(MarkerClaimException.class);
        }

        @Test
        @DisplayName("does NOT throw when a WORKSPACE descendant exists within depth — "
                + "descendant scan is deliberately VAULT-only")
        void workspaceDescendantWithinDepth_doesNotThrow(TempDirs tempDirs) throws Exception {
            Path candidate = tempDirs.newDir("MarkerServiceTest", "nesting-descendant-workspace");
            Path nested = candidate.resolve("sub-workspace");
            Files.createDirectories(nested);
            WorkspaceMarker marker = WorkspaceMarker.create("ws-id", "NestedWorkspace", "2026-01-01T00:00:00");
            markerService.claim(MarkerType.WORKSPACE, nested.toString(), marker);

            markerService.checkNoNestingConflict(candidate.toString(), MarkerType.VAULT, 3);
            // no exception = pass — only VAULT markers are reported by descendant scan
        }

        @Test
        @DisplayName("does not throw when the marked descendant is beyond the configured depth")
        void descendantBeyondDepth_doesNotThrow(TempDirs tempDirs) throws Exception {
            Path candidate = tempDirs.newDir("MarkerServiceTest", "nesting-toodeep");
            Path tooDeep = candidate.resolve("level1").resolve("level2").resolve("level3");
            Files.createDirectories(tooDeep);
            VaultMarker marker = VaultMarker.create("id-1", "Bob/toodeep", "/some/catalog.json", "2026-01-01T00:00:00");
            markerService.claim(MarkerType.VAULT, tooDeep.toString(), marker);

            markerService.checkNoNestingConflict(candidate.toString(), MarkerType.VAULT, 2);
            // no exception = pass — level3 marker is outside a depth-2 scan
        }

        @Test
        @DisplayName("the no-argument overload uses the depth configured at construction")
        void defaultOverload_usesConfiguredDepth(TempDirs tempDirs) throws Exception {
            MarkerService shallow = markerServiceConfiguredWithNonDefaultDepth(2);
            Path candidate = tempDirs.newDir("MarkerServiceTest", "nesting-default-depth");
            Path tooDeep = candidate.resolve("level1").resolve("level2").resolve("level3");
            Files.createDirectories(tooDeep);
            VaultMarker marker = VaultMarker.create("id-1", "Bob/toodeep", "/some/catalog.json", "2026-01-01T00:00:00");
            shallow.claim(MarkerType.VAULT, tooDeep.toString(), marker);

            shallow.checkNoNestingConflict(candidate.toString(), MarkerType.VAULT);
            // no exception = pass — same as passing maxDepth=2 explicitly
        }
    }
}
