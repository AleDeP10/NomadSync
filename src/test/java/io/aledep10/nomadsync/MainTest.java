package io.aledep10.nomadsync;

import io.aledep10.nomadsync.cli.VaultCli;
import io.aledep10.nomadsync.config.NomadPropertiesLoader;
import io.aledep10.nomadsync.exception.*;
import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.service.MarkerService;
import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.vault.Vault;
import io.aledep10.nomadsync.service.GitignoreService;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.service.VaultService;
import io.aledep10.nomadsync.util.ClassFailureTracker;
import io.aledep10.nomadsync.util.TempDirCleanupExtension;
import io.aledep10.nomadsync.util.TempDirs;
import io.aledep10.nomadsync.util.TestUtil;
import io.aledep10.nomadsync.util.TestVault;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Main} private static handler methods.
 *
 * <p>All methods under test are private and accessed via reflection through
 * the {@link TestUtil#invoke} helper. This approach avoids changing production visibility
 * while keeping test coverage granular at the handler level.</p>
 *
 * <p>{@link System#out} is redirected to {@link #outputStream} in {@link #setUp}
 * and restored in {@link #tearDown} — tests that assert on console output read
 * from {@code outputStream.toString()}.</p>
 *
 * <h2>Cross-platform paths</h2>
 * <p>Tests that construct paths use {@link TestUtil#createPath(String...)} to build
 * OS-native separators, consistent with {@link Vault}'s internal normalisation.</p>
 *
 * <h2>Exit codes</h2>
 * <p>All {@code handleVault*}, {@code handleStatus}, and {@code handleConfig}
 * methods return an {@code int}: {@code 0} success, {@code 1} error, {@code 2}
 * no-op (e.g. no changes requested, user aborted a confirmation).</p>
 *
 * <h2>Two vaults, two purposes</h2>
 * <p>{@code sharedVault} (static, created once in {@code @BeforeAll}) exists
 * only to give {@link #logService} a place to write its log file — cleaned up
 * once in {@code @AfterAll}, only if every test in this class passed (see
 * {@link ClassFailureTracker}).</p>
 *
 * <p>{@code testVault} (instance field, obtained fresh per test via the
 * injected {@link TempDirs#newVault}) provides {@code configDir}/
 * {@code catalogFile} for {@link #vaultService} — freshly isolated on every
 * single test, rather than relying on no test ever calling
 * {@code vaultService.load()} to avoid cross-test contamination of a shared
 * catalog file (the same class of risk found and fixed in
 * {@code VaultServiceTest}).</p>
 *
 * <p>Any additional ad-hoc directory a test needs (a vault's real content
 * directory for {@code handleVaultCreate}/{@code handleVaultAdd}/
 * {@code handleVaultRelocate} scenarios) is obtained via the same injected
 * {@link TempDirs}, registered for conditional cleanup — deleted automatically
 * if the test passes, left on disk for inspection if it fails.</p>
 */
@ExtendWith({TempDirCleanupExtension.class, ClassFailureTracker.class})
@DisplayName("Unit tests for Main")
class MainTest {

    static TestVault sharedVault;
    static LogService logService;

    TestVault testVault;
    VaultService vaultService;
    Properties properties;
    PrintStream originalOut;
    PrintStream originalErr;
    ByteArrayOutputStream outputStream;

    @BeforeAll
    static void prepareSharedState() throws IOException {
        sharedVault = TestUtil.getTestVault("MainTest-shared");
        logService  = new LogService(NomadPropertiesLoader.forTesting(TestUtil.forLogService(
                sharedVault, LogLevel.DEBUG)), sharedVault.rootPath());
    }

    @AfterAll
    static void tearDownAll(ExtensionContext context) throws IOException {
        logService.close();
        if (!ClassFailureTracker.anyTestFailed(context)) {
            TestUtil.cleanup(sharedVault);
        }
    }

    @BeforeEach
    void setUp(TempDirs tempDirs) throws IOException {
        testVault = tempDirs.newVault("MainTest");
        properties = new Properties();
        GitignoreService gitignoreService = new GitignoreService(logService);
        MarkerService markerService = new MarkerService(NomadPropertiesLoader.forTesting(properties), logService);
        vaultService = new VaultService(testVault.vaultPath(), markerService, gitignoreService, logService);

        originalOut = System.out;
        originalErr = System.err;
        outputStream = new ByteArrayOutputStream();
        PrintStream captured = new PrintStream(outputStream);
        System.setOut(captured);
        System.setErr(captured);
    }

    @AfterEach
    void tearDown() {
        // Unconditional — restoring stdout/stderr must always happen regardless
        // of test outcome. testVault and every ad-hoc directory created via
        // tempDirs.newDir(...) are registered with the injected TempDirs and
        // cleaned up together, conditionally, by TempDirCleanupExtension.
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    // ── parseArgs ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseArgs")
    class ParseArgsTests {

        // ── reflection helpers for the private ParsedArgs record ───────────────

        private Object invokeParseArgs(String[] args) {
            return TestUtil.invoke(Main.class, "parseArgs", new Class<?>[]{String[].class}, (Object) args);
        }

        private boolean isFailure(Object parsedArgs) throws Exception {
            Method m = parsedArgs.getClass().getDeclaredMethod("isFailure");
            m.setAccessible(true);
            return (boolean) m.invoke(parsedArgs);
        }

        private String getCommand(Object parsedArgs) throws Exception {
            Method m = parsedArgs.getClass().getDeclaredMethod("command");
            m.setAccessible(true);
            return (String) m.invoke(parsedArgs);
        }

        @SuppressWarnings("unchecked")
        private Map<String, String> getFlags(Object parsedArgs) throws Exception {
            Method m = parsedArgs.getClass().getDeclaredMethod("flags");
            m.setAccessible(true);
            return (Map<String, String>) m.invoke(parsedArgs);
        }

        private Integer getErrorExitCode(Object parsedArgs) throws Exception {
            Method m = parsedArgs.getClass().getDeclaredMethod("errorExitCode");
            m.setAccessible(true);
            return (Integer) m.invoke(parsedArgs);
        }

        // ── args.length < 1 ──────────────────────────────────────────────────

        @Test
        @DisplayName("returns failure(1) and prints usage when no arguments are given")
        void emptyArgs_returnsFailureWithUsage() throws Exception {
            Object result = invokeParseArgs(new String[]{});

            assertThat(isFailure(result)).isTrue();
            assertThat(getErrorExitCode(result)).isEqualTo(1);
            assertThat(outputStream.toString()).contains("Usage: java -jar NomadSync.jar");
        }

        // ── basic parsing, non-vault command ────────────────────────────────

        @Test
        @DisplayName("parses a simple command with flags, no positional subcommand")
        void simpleCommand_parsesFlags() throws Exception {
            Object result = invokeParseArgs(new String[] {
                    "pull",
                    "--" + VaultCli.FLAG_VAULT + "=Alice/portfolio",
                    "--" + Main.FLAG_WORKSPACE_PATH + "=/path/to/workspace"});

            assertThat(isFailure(result)).isFalse();
            assertThat(getCommand(result)).isEqualTo("pull");
            Map<String, String> flags = getFlags(result);
            assertThat(flags.get(VaultCli.FLAG_VAULT)).isEqualTo("Alice/portfolio");
            assertThat(flags.get(Main.FLAG_WORKSPACE_PATH)).isEqualTo("/path/to/workspace");
            assertThat(flags.containsKey("sub")).isFalse();
        }

        // ── "sub" extraction for vault subcommands ──────────────────────────

        @Test
        @DisplayName("injects \"sub\" when the second argument is a positional vault subcommand")
        void vaultWithPositionalSubcommand_injectsSubKey() throws Exception {
            Object result = invokeParseArgs(new String[]{
                    VaultCli.COMMAND,
                    "relocate",
                    "--" + VaultCli.FLAG_VAULT + "=x", "--path=/y"});

            assertThat(isFailure(result)).isFalse();
            Map<String, String> flags = getFlags(result);
            assertThat(flags.get("sub")).isEqualTo("relocate");
            assertThat(flags.get(VaultCli.FLAG_VAULT)).isEqualTo("x");
            assertThat(flags.get("path")).isEqualTo("/y");
        }

        @Test
        @DisplayName("does not inject \"sub\" when the second argument is itself a flag")
        void vaultWithFlagAsSecondArg_doesNotInjectSub() throws Exception {
            Object result = invokeParseArgs(new String[]{VaultCli.COMMAND, "--" + VaultCli.FLAG_VAULT + "=x"});

            Map<String, String> flags = getFlags(result);
            assertThat(flags.containsKey("sub")).isFalse();
            assertThat(flags.get(VaultCli.FLAG_VAULT)).isEqualTo("x");
        }

        @Test
        @DisplayName("does not inject \"sub\" when \"vault\" is the only argument")
        void vaultAlone_doesNotInjectSub() throws Exception {
            Object result = invokeParseArgs(new String[]{VaultCli.COMMAND});

            assertThat(isFailure(result)).isFalse();
            assertThat(getFlags(result).containsKey("sub")).isFalse();
        }

        // ── duplicate flag detection ────────────────────────────────────────

        @Test
        @DisplayName("last value wins on a duplicated flag, and a single warning is printed")
        void duplicateFlag_lastValueWinsAndWarns() throws Exception {
            Object result = invokeParseArgs(new String[]{VaultCli.COMMAND, "add", "--owner=Alice", "--owner=Bob"});

            assertThat(getFlags(result).get("owner")).isEqualTo("Bob");
            assertThat(outputStream.toString())
                    .contains("Warning: --owner was specified more than once");
        }

        @Test
        @DisplayName("prints exactly one warning line per duplicated key, not one per occurrence")
        void tripleDuplicateFlag_printsOnlyOneWarningLine() {
            invokeParseArgs(new String[]{VaultCli.COMMAND, "add", "--owner=Alice", "--owner=Bob", "--owner=Carol"});

            String output = outputStream.toString();
            int occurrences = output.split("Warning: --owner was specified more than once", -1).length - 1;
            assertThat(occurrences).isEqualTo(1);
        }

        @Test
        @DisplayName("does not warn when --force is repeated — exempt as a harmless pure flag")
        void repeatedForce_doesNotWarn() throws Exception {
            Object result = invokeParseArgs(new String[]{
                    VaultCli.COMMAND, "relocate", "--" + VaultCli.FLAG_VAULT + "=x", "--force", "--force"});

            assertThat(isFailure(result)).isFalse();
            assertThat(outputStream.toString()).doesNotContain("--force was specified more than once");
        }

        // ── stray argument detection — the --path-without-'=' regression ───

        @Test
        @DisplayName("REGRESSION: a value with no leading '--' (missing '=' after a flag) "
                + "is rejected as a stray argument, not silently swallowed as a flag's value")
        void strayArgument_missingEquals_returnsFailure() throws Exception {
            Object result = invokeParseArgs(new String[]{VaultCli.COMMAND, "relocate",
                    "--" + VaultCli.FLAG_VAULT + "=nomad-test-vault", "--path", "C:\\vaults\\nomad-test"});

            assertThat(isFailure(result)).isTrue();
            assertThat(getErrorExitCode(result)).isEqualTo(1);
            String output = outputStream.toString();
            assertThat(output).contains("Unrecognized argument(s)");
            assertThat(output).contains("did you forget '='");
        }

        @Test
        @DisplayName("a flag with '=' but no following stray token is fine, even if its value is blank")
        void flagWithEqualsButBlankValue_isNotAStrayArgument() throws Exception {
            Object result = invokeParseArgs(new String[]{
                    VaultCli.COMMAND, "update",
                    "--" + VaultCli.FLAG_VAULT + "=x", "--git.token="});

            assertThat(isFailure(result)).isFalse();
            assertThat(getFlags(result).get("git.token")).isEqualTo("");
        }

        @Test
        @DisplayName("a flag with no '=' at all captures a blank value — distinct from a stray token, "
                + "which has no leading '--'")
        void flagWithNoEqualsSign_capturesBlankValue() throws Exception {
            Object result = invokeParseArgs(new String[]{
                    VaultCli.COMMAND, "update", "--" + VaultCli.FLAG_VAULT + "=x", "--git.token"});

            assertThat(isFailure(result)).isFalse();
            assertThat(getFlags(result).get("git.token")).isEqualTo("");
        }

        // ── value containing '=' ────────────────────────────────────────────

        @Test
        @DisplayName("splits only on the FIRST '=' — a value containing '=' is preserved whole")
        void valueContainingEqualsSign_isPreservedWhole() throws Exception {
            Object result = invokeParseArgs(new String[]{"pull", "--git.token=abc=def=ghi"});

            assertThat(getFlags(result).get("git.token")).isEqualTo("abc=def=ghi");
        }
    }

    // ── operationToEventType ──────────────────────────────────────────────────

    @Nested
    @DisplayName("operationToEventType")
    class OperationToEventTypeTests {

        @Test
        @DisplayName("pull returns PULL_LOGON")
        void pull_returnsPullLogon() {
            EventType eventType = (EventType) TestUtil.invoke(Main.class, "operationToEventType",
                    new Class<?>[]{String.class, LogService.class}, "pull", logService);
            assertThat(eventType).isEqualTo(EventType.PULL_LOGON);
        }

        @Test
        @DisplayName("autosave returns null")
        void autosave_returnsNull() {
            Object eventType = TestUtil.invoke(Main.class, "operationToEventType",
                    new Class<?>[]{String.class, LogService.class}, "autosave", logService);
            assertThat(eventType).isNull();
        }

        @Test
        @DisplayName("unknown operation returns null")
        void unknown_returnsNull() {
            Object eventType = TestUtil.invoke(Main.class, "operationToEventType",
                    new Class<?>[]{String.class, LogService.class}, "unknown", logService);
            assertThat(eventType).isNull();
        }
    }
}