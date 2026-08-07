package io.aledep10.nomadsync.config;

import io.aledep10.nomadsync.exception.ConfigException;
import io.aledep10.nomadsync.util.TempDirCleanupExtension;
import io.aledep10.nomadsync.util.TempDirs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link NomadPropertiesLoader}.
 */
@ExtendWith(TempDirCleanupExtension.class)
@DisplayName("Unit tests for NomadPropertiesLoader")
class NomadPropertiesLoaderTest {

    Path installDir;

    @BeforeEach
    void setUp(TempDirs tempDirs) throws IOException {
        installDir = tempDirs.newDir("NomadPropertiesLoaderTest", "install");
    }

    private void writeProperties(Path dir, String... keyValuePairs) throws IOException {
        Files.createDirectories(dir);
        Properties properties = new Properties();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            properties.setProperty(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        try (OutputStream out = Files.newOutputStream(dir.resolve(
                dir.equals(installDir)
                ? NomadPropertiesLoader.INSTALL_CONFIG_FILE_NAME
                : NomadPropertiesLoader.WORKSPACE_CONFIG_FILE_NAME))) {
            properties.store(out, null);
        }
    }

    // ── constructor ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("constructor")
    class ConstructorTests {

        @Test
        @DisplayName("loads installProperties from installDir/config.properties")
        void loadsInstallProperties() throws IOException, ConfigException {
            writeProperties(installDir, "git.executable", "git");

            NomadPropertiesLoader loader = new NomadPropertiesLoader(installDir);

            assertThat(loader.get("git.executable", "fallback")).isEqualTo("git");
        }

        @Test
        @DisplayName("throws IOException when config.properties is absent from installDir")
        void missingInstallFile_throws() {
            assertThatThrownBy(() -> new NomadPropertiesLoader(installDir))
                    .isInstanceOf(ConfigException.class);
        }
    }

    // ── loadWorkspaceOverrides ───────────────────────────────────────────

    @Nested
    @DisplayName("loadWorkspaceOverrides")
    class LoadWorkspaceOverridesTests {

        @Test
        @DisplayName("workspace value overrides the install value for the same key")
        void overridesInstallValue() throws IOException, ConfigException {
            writeProperties(installDir, "marker.maxNestingDepth", "6");
            NomadPropertiesLoader loader = new NomadPropertiesLoader(installDir);

            Path workspaceConfigDir = installDir.resolve("ws-1").resolve(".nomadsync-workspace");
            writeProperties(workspaceConfigDir, "marker.maxNestingDepth", "12");
            loader.loadWorkspaceOverrides(workspaceConfigDir);

            assertThat(loader.getInt("marker.maxNestingDepth", -1)).isEqualTo(12);
        }

        @Test
        @DisplayName("install keys not present in workspace overrides remain visible")
        void installOnlyKeysSurvive() throws IOException, ConfigException {
            writeProperties(installDir, "git.executable", "git", "marker.maxNestingDepth", "6");
            NomadPropertiesLoader loader = new NomadPropertiesLoader(installDir);

            Path workspaceConfigDir = installDir.resolve("ws-1").resolve(".nomadsync-workspace");
            writeProperties(workspaceConfigDir, "marker.maxNestingDepth", "12");
            loader.loadWorkspaceOverrides(workspaceConfigDir);

            assertThat(loader.get("git.executable", "fallback")).isEqualTo("git");
        }

        @Test
        @DisplayName("a missing workspace config.properties is not an error - no overrides applied")
        void missingWorkspaceFile_noOverrides() throws IOException, ConfigException {
            writeProperties(installDir, "marker.maxNestingDepth", "6");
            NomadPropertiesLoader loader = new NomadPropertiesLoader(installDir);

            Path workspaceConfigDir = installDir.resolve("ws-1").resolve(".nomadsync-workspace");
            Files.createDirectories(workspaceConfigDir); // dir exists, no config.properties inside

            loader.loadWorkspaceOverrides(workspaceConfigDir);

            assertThat(loader.getInt("marker.maxNestingDepth", -1)).isEqualTo(6);
        }

        @Test
        @DisplayName("a second call replaces the previous workspace overrides, not merges with them")
        void secondCall_replacesPreviousOverrides() throws IOException, ConfigException {
            writeProperties(installDir, "marker.maxNestingDepth", "6");
            NomadPropertiesLoader loader = new NomadPropertiesLoader(installDir);

            Path firstWorkspace = installDir.resolve("ws-1").resolve(".nomadsync-workspace");
            writeProperties(firstWorkspace, "marker.maxNestingDepth", "12", "git.executable", "git-ws1");
            loader.loadWorkspaceOverrides(firstWorkspace);

            Path secondWorkspace = installDir.resolve("ws-2").resolve(".nomadsync-workspace");
            writeProperties(secondWorkspace, "marker.maxNestingDepth", "20");
            loader.loadWorkspaceOverrides(secondWorkspace);

            assertThat(loader.getInt("marker.maxNestingDepth", -1)).isEqualTo(20);
            // git.executable was only ever set by the FIRST workspace's overrides —
            // the second call must not retain it, only install/second-workspace apply now.
            assertThat(loader.get("git.executable", "fallback")).isEqualTo("fallback");
        }
    }

    // ── typed accessors ──────────────────────────────────────────────────

    @Nested
    @DisplayName("typed accessors")
    class TypedAccessorTests {

        NomadPropertiesLoader loader;

        @BeforeEach
        void setUpLoader() throws IOException, ConfigException {
            writeProperties(installDir,
                    "marker.maxNestingDepth", "8",
                    "log.writers", "console,file",
                    "log.level", "DEBUG",
                    "blank.value", "");
            loader = new NomadPropertiesLoader(installDir);
        }

        @Test
        @DisplayName("get: returns the value when present")
        void get_returnsValue() {
            assertThat(loader.get("log.writers", "fallback")).isEqualTo("console,file");
        }

        @Test
        @DisplayName("get: returns default when the key is absent")
        void get_absentKey_returnsDefault() {
            assertThat(loader.get("does.not.exist", "fallback")).isEqualTo("fallback");
        }

        @Test
        @DisplayName("get: returns default when the value is blank")
        void get_blankValue_returnsDefault() {
            assertThat(loader.get("blank.value", "fallback")).isEqualTo("fallback");
        }

        @Test
        @DisplayName("getInt: parses a valid integer")
        void getInt_parsesValue() {
            assertThat(loader.getInt("marker.maxNestingDepth", -1)).isEqualTo(8);
        }

        @Test
        @DisplayName("getInt: returns default on unparsable value")
        void getInt_unparsable_returnsDefault() {
            assertThat(loader.getInt("log.writers", -1)).isEqualTo(-1);
        }

        @Test
        @DisplayName("getBoolean: recognises true/false case-insensitively")
        void getBoolean_recognisesValue() throws IOException, ConfigException {
            writeProperties(installDir, "flag.enabled", "TRUE");
            NomadPropertiesLoader reloaded = new NomadPropertiesLoader(installDir);

            assertThat(reloaded.getBoolean("flag.enabled", false)).isTrue();
        }

        @Test
        @DisplayName("getBoolean: returns default on unrecognised value")
        void getBoolean_unrecognised_returnsDefault() {
            assertThat(loader.getBoolean("log.level", true)).isTrue();
        }

        @Test
        @DisplayName("getEnum: matches a constant case-insensitively")
        void getEnum_matchesConstant() {
            assertThat(loader.getEnum("log.level", TestLevel.class, TestLevel.INFO))
                    .isEqualTo(TestLevel.DEBUG);
        }

        @Test
        @DisplayName("getEnum: returns default when no constant matches")
        void getEnum_noMatch_returnsDefault() {
            assertThat(loader.getEnum("log.writers", TestLevel.class, TestLevel.INFO))
                    .isEqualTo(TestLevel.INFO);
        }

        enum TestLevel { DEBUG, INFO, WARN, ERROR }
    }
}
