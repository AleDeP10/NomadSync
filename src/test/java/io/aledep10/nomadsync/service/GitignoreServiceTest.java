package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.gitignore.AppPatterns;
import io.aledep10.nomadsync.gitignore.GitignorePattern;
import io.aledep10.nomadsync.gitignore.PatternLevel;
import io.aledep10.nomadsync.gitignore.SystemPattern;
import io.aledep10.nomadsync.gitignore.VaultPatterns;
import io.aledep10.nomadsync.gitignore.exception.GitignoreException;
import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.util.TestUtil;
import io.aledep10.nomadsync.util.TestVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for {@link GitignoreService}.
 *
 * <p>Each test operates on a real temporary {@code .gitignore} file created inside
 * the shared {@link TestVault}. The vault directory is cleaned in {@code @AfterEach}.</p>
 *
 * <p>{@link GitignoreService} is stateless — the same instance is shared across
 * all tests without risk of cross-test contamination.</p>
 *
 * <h2>Coverage strategy</h2>
 * <ul>
 *   <li>{@code load()} — file presence, three-section parsing, SYSTEM restore,
 *       APP reconciliation, USER classification, comment/blank filtering,
 *       duplicate deduplication, canonical format output, two-vault isolation.</li>
 *   <li>{@code save()} — APP negated update, SYSTEM negation guard, USER replace,
 *       section preservation, empty USER section, two-vault isolation.</li>
 *   <li>{@code forSnapshot()} — negated exclusion, SYSTEM always included,
 *       USER non-negated included, empty gitignore baseline.</li>
 *   <li>Private helpers tested indirectly via {@code load()} — {@code cleanLine},
 *       {@code cloneAppPatterns}, {@code cloneSystemPatterns}, {@code serializeGitignore}.</li>
 * </ul>
 */
class GitignoreServiceTest {

    static TestVault testVault;
    static LogService logService;
    static GitignoreService gitignoreService;

    @BeforeAll
    static void prepareSharedState() throws IOException {
        testVault        = TestUtil.getTestVault("GitignoreServiceTest");
        logService       = new LogService(TestUtil.forLogService(testVault, LogLevel.DEBUG), testVault.rootPath());
        gitignoreService = new GitignoreService(logService);
    }

    @BeforeEach
    void setUp() throws IOException {
        // ricrea solo la vault directory e cancella il gitignore
        Files.createDirectories(testVault.vaultPath());
        Files.deleteIfExists(testVault.gitignoreFilePath());
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(testVault.gitignoreFilePath());
        // NON cleanup completo — testVault è condiviso e il log file serve
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void writeGitignore(String content) throws IOException {
        Files.writeString(testVault.gitignoreFilePath(), content);
    }

    private TestVault secondVault() throws IOException {
        return TestUtil.getTestVault("GitignoreServiceTest-B");
    }

    // ── load() — file presence ────────────────────────────────────────────────

    /**
     * Verifies that load() on a missing .gitignore creates the file from scratch
     * with all four SYSTEM patterns present.
     */
    @Test
    void load_gitignoreNotPresent_createsFromScratch() throws Exception {
        assertThat(Files.exists(testVault.gitignoreFilePath())).isFalse();

        VaultPatterns result = gitignoreService.load(testVault.vaultPath());

        assertThat(Files.exists(testVault.gitignoreFilePath())).isTrue();
        assertThat(result.getSystem().isEmpty()).isFalse();
        List<String> systemNames = result.getSystem().stream()
                .map(GitignorePattern::getPattern).toList();
        assertThat(systemNames).asList()
                .contains(".git", ".DS_Store", "Thumbs.db", "desktop.ini");
    }

    /**
     * Verifies that load() on an existing .gitignore returns patterns in all three sections.
     */
    @Test
    void load_gitignorePresent_loadsAllThreeSections() throws Exception {
        writeGitignore(".git\n!.obsidian/workspace\n*.tmp");

        VaultPatterns result = gitignoreService.load(testVault.vaultPath());

        assertThat(result.getSystem().isEmpty()).isFalse();
        assertThat(result.getApp().isEmpty()).isFalse();
        assertThat(result.getUser().isEmpty()).isFalse();
    }

    // ── load() — SYSTEM restore ───────────────────────────────────────────────

    /**
     * Verifies that a missing SYSTEM pattern is restored and a warning is logged.
     */
    @Test
    void load_missingSystemPattern_restoresAndWarns() throws Exception {
        writeGitignore(".DS_Store\nThumbs.db\ndesktop.ini");

        VaultPatterns result = gitignoreService.load(testVault.vaultPath());

        List<String> systemPatterns = result.getSystem().stream()
                .map(GitignorePattern::getPattern).toList();
        assertThat(systemPatterns).asList().contains(".git");
        assertThat(Files.readString(testVault.logFilePath())).contains(".git");
    }

    // ── load() — APP reconciliation ───────────────────────────────────────────

    /**
     * Verifies that an APP pattern found in the file without "!" overrides the
     * default negated=true defined in APP_PATTERN_DEFINITIONS.
     */
    @Test
    void load_appPatternNegatedInFile_overridesDefault() throws Exception {
        writeGitignore(".obsidian/workspace");

        VaultPatterns result = gitignoreService.load(testVault.vaultPath());

        boolean negated = result.getApp().stream()
                .flatMap(a -> a.getPatterns().stream())
                .filter(p -> p.getPattern().equals(".obsidian/workspace"))
                .findFirst().orElseThrow().isNegated();
        assertThat(negated).isFalse();
    }

    /**
     * Verifies that an APP pattern absent from the file retains its default negated value.
     */
    @Test
    void load_appPatternNotInFile_usesDefaultNegated() throws Exception {
        writeGitignore(".git");

        VaultPatterns result = gitignoreService.load(testVault.vaultPath());

        boolean negated = result.getApp().stream()
                .flatMap(a -> a.getPatterns().stream())
                .filter(p -> p.getPattern().equals(".obsidian/workspace"))
                .findFirst().orElseThrow().isNegated();
        assertThat(negated).isTrue();
    }

    /**
     * Verifies that "!.obsidian/workspace" in the file is loaded as negated=true.
     */
    @Test
    void load_appPatternManuallyNegated_preservesNegated() throws Exception {
        writeGitignore("!.obsidian/workspace");

        VaultPatterns result = gitignoreService.load(testVault.vaultPath());

        boolean negated = result.getApp().stream()
                .flatMap(a -> a.getPatterns().stream())
                .filter(p -> p.getPattern().equals(".obsidian/workspace"))
                .findFirst().orElseThrow().isNegated();
        assertThat(negated).isTrue();
    }

    // ── load() — USER classification ──────────────────────────────────────────

    /**
     * Verifies that an unknown pattern is classified as USER.
     */
    @Test
    void load_unknownPattern_classifiedAsUser() throws Exception {
        writeGitignore("*.tmp");

        VaultPatterns result = gitignoreService.load(testVault.vaultPath());

        assertThat(result.getUser().stream()
                .anyMatch(p -> p.getPattern().equals("*.tmp"))).isTrue();
    }

    /**
     * Verifies that comment lines are not loaded as patterns in any section.
     */
    @Test
    void load_commentLines_ignored() throws Exception {
        writeGitignore("# this is a comment\n*.tmp");

        VaultPatterns result = gitignoreService.load(testVault.vaultPath());

        assertThat(result.allPatterns().stream()
                .noneMatch(p -> p.getPattern().startsWith("#"))).isTrue();
        assertThat(result.getUser().stream()
                .anyMatch(p -> p.getPattern().equals("*.tmp"))).isTrue();
    }

    /**
     * Verifies that blank lines produce no patterns in any section.
     */
    @Test
    void load_blankLines_ignored() throws Exception {
        writeGitignore("\n\n*.tmp\n\n");

        VaultPatterns result = gitignoreService.load(testVault.vaultPath());

        assertThat(result.allPatterns().stream()
                .noneMatch(p -> p.getPattern().isBlank())).isTrue();
        assertThat(result.getUser().stream()
                .anyMatch(p -> p.getPattern().equals("*.tmp"))).isTrue();
    }

    // ── load() — deduplication ────────────────────────────────────────────────

    /**
     * Verifies that a duplicate USER pattern with divergent negated values keeps only
     * the last occurrence — last wins via TreeMap.put semantics.
     */
    @Test
    void load_duplicatePattern_lastWins() throws Exception {
        writeGitignore("*.tmp\n!*.tmp");

        VaultPatterns result = gitignoreService.load(testVault.vaultPath());

        List<GitignorePattern> matching = result.getUser().stream()
                .filter(p -> p.getPattern().equals("*.tmp")).toList();
        assertThat(matching.size()).isEqualTo(1);
        assertThat(matching.getFirst().isNegated()).isTrue();
    }

    // ── load() — edge cases ───────────────────────────────────────────────────

    /**
     * Verifies that an empty .gitignore returns only default SYSTEM and APP patterns
     * with no USER patterns.
     */
    @Test
    void load_emptyFile_returnsDefaultPatternsOnly() throws Exception {
        writeGitignore("");

        VaultPatterns result = gitignoreService.load(testVault.vaultPath());

        List<String> systemPatterns = result.getSystem().stream()
                .map(GitignorePattern::getPattern).toList();
        assertThat(systemPatterns).asList()
                .containsExactlyInAnyOrder(".git", ".DS_Store", "Thumbs.db", "desktop.ini");
        assertThat(result.getApp().isEmpty()).isFalse();
        assertThat(result.getUser().isEmpty()).isTrue();
    }

    /**
     * Verifies that a file with only USER patterns still includes all SYSTEM
     * and APP defaults in the result.
     */
    @Test
    void load_fileWithOnlyUserPatterns_systemAndAppAddedFromDefinitions() throws Exception {
        writeGitignore("*.tmp\n*.log");

        VaultPatterns result = gitignoreService.load(testVault.vaultPath());

        assertThat(result.getSystem().size()).isEqualTo(4);
        assertThat(result.getApp().isEmpty()).isFalse();
        assertThat(result.getUser().stream()
                .map(GitignorePattern::getPattern).toList()).asList()
                .contains("*.tmp", "*.log");
    }

    /**
     * Verifies that load() writes the canonical three-section format to disk
     * in the correct order: SYSTEM → APP → USER.
     */
    @Test
    void load_writesCanonicalThreeSectionFormat() throws Exception {
        gitignoreService.load(testVault.vaultPath());

        String content = Files.readString(testVault.gitignoreFilePath());
        int systemIdx = content.indexOf("# SYSTEM PATTERNS - DO NOT TOUCH!");
        int appIdx    = content.indexOf("# APP PATTERNS");
        int userIdx   = content.indexOf("# USER PATTERNS");
        assertThat(systemIdx).isGreaterThanOrEqualTo(0);
        assertThat(appIdx).isGreaterThan(systemIdx);
        assertThat(userIdx).isGreaterThan(appIdx);
    }

    // ── load() — two-vault isolation ──────────────────────────────────────────

    /**
     * Verifies that loading two vaults with different USER patterns produces
     * isolated results — vault A patterns do not appear in vault B and vice versa.
     */
    @Test
    void load_twoVaults_stateIsIsolated() throws Exception {
        TestVault vaultB = secondVault();
        try {
            writeGitignore("*.tmp");
            Files.writeString(vaultB.gitignoreFilePath(), "*.log");

            VaultPatterns resultA = gitignoreService.load(testVault.vaultPath());
            VaultPatterns resultB = gitignoreService.load(vaultB.vaultPath());

            assertThat(resultA.getUser().stream()
                    .anyMatch(p -> p.getPattern().equals("*.tmp"))).isTrue();
            assertThat(resultA.getUser().stream()
                    .noneMatch(p -> p.getPattern().equals("*.log"))).isTrue();
            assertThat(resultB.getUser().stream()
                    .anyMatch(p -> p.getPattern().equals("*.log"))).isTrue();
            assertThat(resultB.getUser().stream()
                    .noneMatch(p -> p.getPattern().equals("*.tmp"))).isTrue();
        } finally {
            TestUtil.cleanup(vaultB);
        }
    }

    // ── save() ────────────────────────────────────────────────────────────────

    /**
     * Verifies that save() updates the negated value of an APP pattern and that
     * a subsequent load() reflects the change.
     */
    @Test
    void save_updatesNegatedOnAppPattern() throws Exception {
        VaultPatterns loaded = gitignoreService.load(testVault.vaultPath());
        loaded.getApp().stream()
                .flatMap(a -> a.getPatterns().stream())
                .filter(p -> p.getPattern().equals(".obsidian/workspace"))
                .forEach(p -> p.setNegated(false));

        gitignoreService.save(testVault.vaultPath(), loaded.allPatterns());
        VaultPatterns reloaded = gitignoreService.load(testVault.vaultPath());

        boolean negated = reloaded.getApp().stream()
                .flatMap(a -> a.getPatterns().stream())
                .filter(p -> p.getPattern().equals(".obsidian/workspace"))
                .findFirst().orElseThrow().isNegated();
        assertThat(negated).isFalse();
    }

    /**
     * Verifies that save() replaces USER patterns and that a subsequent load()
     * returns only the new USER patterns.
     */
    @Test
    void save_replacesUserPatterns() throws Exception {
        writeGitignore("*.tmp");
        VaultPatterns loaded = gitignoreService.load(testVault.vaultPath());
        List<GitignorePattern> newPatterns = loaded.allPatterns().stream()
                .filter(p -> !p.getPattern().equals("*.tmp"))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        newPatterns.add(new GitignorePattern("*.log", PatternLevel.USER, null, false));
        newPatterns.add(new GitignorePattern("*.bak", PatternLevel.USER, null, false));

        gitignoreService.save(testVault.vaultPath(), newPatterns);
        VaultPatterns reloaded = gitignoreService.load(testVault.vaultPath());

        List<String> userPatterns = reloaded.getUser().stream()
                .map(GitignorePattern::getPattern).toList();
        assertThat(userPatterns).asList().contains("*.log", "*.bak");
        assertThat(userPatterns).asList().doesNotContain("*.tmp");
    }

    /**
     * Verifies that save() preserves all three sections — none is dropped.
     */
    @Test
    void save_preservesAllThreeSections() throws Exception {
        VaultPatterns loaded = gitignoreService.load(testVault.vaultPath());

        gitignoreService.save(testVault.vaultPath(), loaded.allPatterns());
        VaultPatterns reloaded = gitignoreService.load(testVault.vaultPath());

        assertThat(reloaded.getSystem().isEmpty()).isFalse();
        assertThat(reloaded.getApp().isEmpty()).isFalse();
    }

    /**
     * Verifies that save() with an empty USER list writes the USER section header
     * but no USER patterns underneath, and that a subsequent load() returns
     * an empty USER list.
     */
    @Test
    void save_emptyUserPatterns_writesEmptyUserSection() throws Exception {
        VaultPatterns loaded = gitignoreService.load(testVault.vaultPath());
        // keep only SYSTEM and APP patterns — drop all USER
        List<GitignorePattern> noUser = loaded.allPatterns().stream()
                .filter(p -> p.getLevel() != PatternLevel.USER)
                .toList();

        gitignoreService.save(testVault.vaultPath(), noUser);

        String content = Files.readString(testVault.gitignoreFilePath());
        assertThat(content).contains("# USER PATTERNS");
        VaultPatterns reloaded = gitignoreService.load(testVault.vaultPath());
        assertThat(reloaded.getUser().isEmpty()).isTrue();
    }

    /**
     * Verifies that saving vault A does not affect vault B's .gitignore.
     */
    @Test
    void save_twoVaults_noStateCrossContamination() throws Exception {
        TestVault vaultB = secondVault();
        try {
            VaultPatterns loadedA = gitignoreService.load(testVault.vaultPath());
            gitignoreService.load(vaultB.vaultPath());

            List<GitignorePattern> patternsA = new java.util.ArrayList<>(loadedA.allPatterns());
            patternsA.add(new GitignorePattern("*.tmp", PatternLevel.USER, null, false));
            gitignoreService.save(testVault.vaultPath(), patternsA);

            VaultPatterns reloadedB = gitignoreService.load(vaultB.vaultPath());
            assertThat(reloadedB.getUser().stream()
                    .noneMatch(p -> p.getPattern().equals("*.tmp"))).isTrue();
        } finally {
            TestUtil.cleanup(vaultB);
        }
    }

    // ── forSnapshot() ─────────────────────────────────────────────────────────

    /**
     * Verifies that forSnapshot() returns exactly as many matchers as there are
     * non-negated patterns across all three sections.
     */
    @Test
    void forSnapshot_returnsOnlyNonNegatedPatterns() throws Exception {
        gitignoreService.load(testVault.vaultPath());
        VaultPatterns vp = gitignoreService.load(testVault.vaultPath());

        long expectedCount = vp.allPatterns().stream().filter(p -> !p.isNegated()).count();
        List<PathMatcher> matchers = gitignoreService.forSnapshot(testVault.vaultPath());

        assertThat(matchers.size()).isEqualTo((int) expectedCount);
    }

    /**
     * Verifies that ".obsidian/workspace" (negated=true by default) is excluded
     * from the snapshot matchers.
     */
    @Test
    void forSnapshot_negatedPatternExcludedFromMatchers() throws Exception {
        gitignoreService.load(testVault.vaultPath());

        List<PathMatcher> matchers = gitignoreService.forSnapshot(testVault.vaultPath());

        assertThat(matchers.stream()
                .noneMatch(m -> m.matches(Path.of(".obsidian").resolve("workspace"))))
                .isTrue();
    }

    /**
     * Verifies that all four SYSTEM patterns always appear in the snapshot matchers.
     */
    @Test
    void forSnapshot_systemPatternsAlwaysInMatchers() throws Exception {
        gitignoreService.load(testVault.vaultPath());

        List<PathMatcher> matchers = gitignoreService.forSnapshot(testVault.vaultPath());

        assertThat(matchers.stream().anyMatch(m -> m.matches(Path.of(".git")))).isTrue();
        assertThat(matchers.stream().anyMatch(m -> m.matches(Path.of(".DS_Store")))).isTrue();
        assertThat(matchers.stream().anyMatch(m -> m.matches(Path.of("Thumbs.db")))).isTrue();
        assertThat(matchers.stream().anyMatch(m -> m.matches(Path.of("desktop.ini")))).isTrue();
    }

    /**
     * Verifies that a non-negated USER pattern produces a matcher that matches
     * the corresponding file path.
     */
    @Test
    void forSnapshot_userPatternNonNegated_includedInMatchers() throws Exception {
        writeGitignore("*.tmp");
        gitignoreService.load(testVault.vaultPath());

        List<PathMatcher> matchers = gitignoreService.forSnapshot(testVault.vaultPath());

        assertThat(matchers.stream()
                .anyMatch(m -> m.matches(Path.of("anything.tmp")))).isTrue();
    }

    /**
     * Verifies that an empty .gitignore produces exactly SYSTEM + non-negated APP
     * matchers and no USER matchers.
     */
    @Test
    void forSnapshot_emptyGitignore_returnsSystemAndNonNegatedAppMatchersOnly() throws Exception {
        writeGitignore("");
        VaultPatterns defaults = gitignoreService.load(testVault.vaultPath());

        long expectedTotal = defaults.allPatterns().stream()
                .filter(p -> !p.isNegated()).count();
        List<PathMatcher> matchers = gitignoreService.forSnapshot(testVault.vaultPath());

        assertThat(matchers.size()).isEqualTo((int) expectedTotal);
        assertThat(matchers.stream().anyMatch(m -> m.matches(Path.of(".git")))).isTrue();
    }

    // ── Helpers — tested indirectly via load() ────────────────────────────────

    /**
     * Verifies cleanLine correctly detects negated=false for a plain pattern.
     */
    @Test
    void cleanLine_plainPattern_negatedFalse() throws Exception {
        writeGitignore("*.tmp");

        VaultPatterns result = gitignoreService.load(testVault.vaultPath());

        assertThat(result.getUser().stream()
                .filter(p -> p.getPattern().equals("*.tmp"))
                .findFirst().orElseThrow().isNegated()).isFalse();
    }

    /**
     * Verifies cleanLine correctly detects negated=true for a "!" prefixed pattern.
     */
    @Test
    void cleanLine_bangPrefix_negatedTrue() throws Exception {
        writeGitignore("!*.tmp");

        VaultPatterns result = gitignoreService.load(testVault.vaultPath());

        assertThat(result.getUser().stream()
                .filter(p -> p.getPattern().equals("*.tmp"))
                .findFirst().orElseThrow().isNegated()).isTrue();
    }

    /**
     * Verifies that whitespace around "!" is handled correctly — negated=true
     * and the pattern is trimmed.
     */
    @Test
    void cleanLine_whitespaceAroundBang_negatedTrueAndPatternTrimmed() throws Exception {
        writeGitignore("  !*.tmp  ");

        VaultPatterns result = gitignoreService.load(testVault.vaultPath());

        GitignorePattern pattern = result.getUser().stream()
                .filter(p -> p.getPattern().equals("*.tmp"))
                .findFirst().orElseThrow();
        assertThat(pattern.isNegated()).isTrue();
        assertThat(pattern.getPattern()).isEqualTo("*.tmp");
    }

    /**
     * Verifies that SYSTEM_PATTERN_DEFINITIONS is not mutated by load() —
     * SystemPattern does not expose setNegated(), so the definitions list
     * is structurally protected. Two consecutive load() calls must return
     * identical SYSTEM patterns.
     */
    @Test
    void cloneSystemPatterns_mutationOnResultDoesNotAffectDefinitions() throws Exception {
        VaultPatterns first  = gitignoreService.load(testVault.vaultPath());
        VaultPatterns second = gitignoreService.load(testVault.vaultPath());

        // both loads must return the same four SYSTEM patterns with negated=false
        List<String> firstNames  = first.getSystem().stream()
                .map(GitignorePattern::getPattern).toList();
        List<String> secondNames = second.getSystem().stream()
                .map(GitignorePattern::getPattern).toList();
        assertThat(firstNames).isEqualTo(secondNames);
        second.getSystem().forEach(p -> assertThat(p.isNegated()).isFalse());
    }

    /**
     * Verifies that mutating the negated flag of an APP pattern returned by load()
     * does not affect APP_PATTERN_DEFINITIONS — cloneAppPatterns() must deep-copy.
     */
    @Test
    void cloneAppPatterns_mutationOnResultDoesNotAffectDefinitions() throws Exception {
        VaultPatterns first = gitignoreService.load(testVault.vaultPath());
        // mutate the clone — flip negated on ".obsidian/workspace"
        first.getApp().stream()
                .flatMap(a -> a.getPatterns().stream())
                .filter(p -> p.getPattern().equals(".obsidian/workspace"))
                .forEach(p -> p.setNegated(false));

        VaultPatterns second = gitignoreService.load(testVault.vaultPath());

        boolean defaultNegated = second.getApp().stream()
                .flatMap(a -> a.getPatterns().stream())
                .filter(p -> p.getPattern().equals(".obsidian/workspace"))
                .findFirst().orElseThrow().isNegated();
        assertThat(defaultNegated).isTrue();
    }

    /**
     * Verifies that serializeGitignore produces a file with all three section headers
     * in the correct order.
     */
    @Test
    void serializeGitignore_outputContainsAllThreeHeaders() throws Exception {
        gitignoreService.load(testVault.vaultPath());

        String content = Files.readString(testVault.gitignoreFilePath());
        assertThat(content).contains("# SYSTEM PATTERNS - DO NOT TOUCH!");
        assertThat(content).contains("# APP PATTERNS");
        assertThat(content).contains("# USER PATTERNS");
    }

    /**
     * Verifies that a negated USER pattern is written with a "!" prefix on disk.
     */
    @Test
    void serializeGitignore_negatedPatternWrittenWithBang() throws Exception {
        writeGitignore("*.tmp");
        VaultPatterns loaded = gitignoreService.load(testVault.vaultPath());
        loaded.getUser().stream()
                .filter(p -> p.getPattern().equals("*.tmp"))
                .forEach(p -> p.setNegated(true));

        gitignoreService.save(testVault.vaultPath(), loaded.allPatterns());

        assertThat(Files.readString(testVault.gitignoreFilePath())).contains("!*.tmp");
    }
}