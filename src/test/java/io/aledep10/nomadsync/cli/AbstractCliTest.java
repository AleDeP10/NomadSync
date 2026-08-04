package io.aledep10.nomadsync.cli;

import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.util.TempDirCleanupExtension;
import io.aledep10.nomadsync.util.TempDirs;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AbstractCli}.
 *
 * <p>Exercised through a minimal concrete subclass ({@link StubCli}) declared
 * below, not through {@code VaultCli}/{@code WorkspaceCli} — this class tests
 * only the shared machinery ({@link AbstractCli#hasUnknownFlags},
 * {@link AbstractCli#hasBlankRequiredFlags}, {@link AbstractCli#hasBlankOptionalValue},
 * {@link AbstractCli#nearestKnownFlag}), not any domain-specific behaviour.
 * Being in the same package as {@link AbstractCli}, tests call its
 * {@code protected} methods directly — no reflection needed, unlike the
 * earlier {@code TestUtil.invoke} approach this move away from static methods
 * makes obsolete for this class.</p>
 */
@ExtendWith(TempDirCleanupExtension.class)
@DisplayName("Unit tests for AbstractCli")
class AbstractCliTest {

    private static final Set<String> KNOWN_FLAGS = Set.of("owner", "name", "path");

    Path tempDir;
    StubCli cli;

    @BeforeEach
    void setUp(TempDirs tempDirs) throws IOException {
        tempDir = tempDirs.newDir("AbstractCliTest", "root");
        LogService logService = new LogService(new Properties(), tempDir);
        cli = new StubCli(logService);
    }

    /**
     * Minimal concrete subclass — the syntax hint and always-checked key are
     * deliberately the same values {@code VaultCli} uses today
     * ({@code --vault=<name|owner/name>}, {@code workspacePath}), so these
     * tests double as a behaviour-preservation check against the pre-refactor
     * static methods.
     */
    private static class StubCli extends AbstractCli {
        StubCli(LogService logService) {
            super(logService);
        }

        @Override
        protected Map<String, String> syntaxHints() {
            return Map.of(VaultCli.FLAG_VAULT, "--vault=<name|owner/name>");
        }

        @Override
        protected int flagSuggestionMaxDistance() {
            return 2;
        }
    }

    @Nested
    @DisplayName("hasUnknownFlags")
    class HasUnknownFlagsTests {

        @Test
        @DisplayName("returns false when every key is known")
        void allKnown_returnsFalse() {
            Map<String, String> flags = Map.of("owner", "Alice", "name", VaultCli.FLAG_VAULT, "path", "/tmp/vault");

            assertThat(cli.hasUnknownFlags(flags, KNOWN_FLAGS, "stubHandler")).isFalse();
        }

        @Test
        @DisplayName("'sub' is always permitted, never reported as unknown")
        void subKey_neverReported() {
            Map<String, String> flags = Map.of("sub", "create", "owner", "Alice", "name", VaultCli.FLAG_VAULT, "path", "/tmp/vault");

            assertThat(cli.hasUnknownFlags(flags, KNOWN_FLAGS, "stubHandler")).isFalse();
        }

        @Test
        @DisplayName("returns true when an unrecognised key is present")
        void unknownKey_returnsTrue() {
            Map<String, String> flags = Map.of("owner", "Alice", "bogus", "x");

            assertThat(cli.hasUnknownFlags(flags, KNOWN_FLAGS, "stubHandler")).isTrue();
        }
    }

    @Nested
    @DisplayName("hasBlankRequiredFlags")
    class HasBlankRequiredFlagsTests {

        @Test
        @DisplayName("returns false when every required key is present and non-blank")
        void allPresent_returnsFalse() {
            Map<String, String> flags = Map.of("owner", "Alice", "name", VaultCli.FLAG_VAULT);

            assertThat(cli.hasBlankRequiredFlags(flags, Set.of("owner", "name"), "stubHandler")).isFalse();
        }

        @Test
        @DisplayName("returns true when a required key is entirely absent")
        void missingKey_returnsTrue() {
            Map<String, String> flags = Map.of("owner", "Alice");

            assertThat(cli.hasBlankRequiredFlags(flags, Set.of("owner", "name"), "stubHandler")).isTrue();
        }

        @Test
        @DisplayName("returns true when a required key is present but blank")
        void blankValue_returnsTrue() {
            Map<String, String> flags = new java.util.HashMap<>();
            flags.put("owner", "");

            assertThat(cli.hasBlankRequiredFlags(flags, Set.of("owner"), "stubHandler")).isTrue();
        }
    }

    @Nested
    @DisplayName("hasBlankOptionalValue")
    class HasBlankOptionalValueTests {

        @Test
        @DisplayName("returns false when a structural key is entirely absent (legitimately optional)")
        void absentStructuralKey_returnsFalse() {
            assertThat(cli.hasBlankOptionalValue(Map.of(), Set.of("path"), "stubHandler")).isFalse();
        }

        @Test
        @DisplayName("returns true when a structural key is present but blank")
        void presentBlankStructuralKey_returnsTrue() {
            Map<String, String> flags = new java.util.HashMap<>();
            flags.put("path", "");

            assertThat(cli.hasBlankOptionalValue(flags, Set.of("path"), "stubHandler")).isTrue();
        }

        @Test
        @DisplayName("returns false when nothing checked is present")
        void nothingPresent_returnsFalse() {
            assertThat(cli.hasBlankOptionalValue(Map.of(), Set.of(), "stubHandler")).isFalse();
        }
    }

    @Nested
    @DisplayName("nearestKnownFlag")
    class NearestKnownFlagTests {

        @Test
        @DisplayName("finds a known flag within the suggestion threshold")
        void closeTypo_findsSuggestion() {
            assertThat(cli.nearestKnownFlag("ownr", KNOWN_FLAGS)).contains("owner");
        }

        @Test
        @DisplayName("returns empty when no known flag is within threshold")
        void unrelatedFlag_returnsEmpty() {
            assertThat(cli.nearestKnownFlag("completelyUnrelatedFlagName", KNOWN_FLAGS)).isEmpty();
        }
    }

    @Nested
    @DisplayName("isCrossDrive")
    class IsCrossDriveTests {

        @Test
        @DisplayName("returns false when source and target share the same filesystem/drive")
        void sameDrive_returnsFalse() throws IOException {
            assertThat(cli.isCrossDrive(tempDir, tempDir.resolve("target"))).isFalse();
        }

        @Test
        @DisplayName("walks up to the nearest existing ancestor when target does not exist yet")
        void nonExistentTarget_walksUpToExistingAncestor() throws IOException {
            Path target = tempDir.resolve("not-yet-created").resolve("nested");

            assertThat(cli.isCrossDrive(tempDir, target)).isFalse();
        }
    }
}
