package io.aledep10.nomadsync;

import io.aledep10.nomadsync.exception.*;
import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.orchestrator.Vault;
import io.aledep10.nomadsync.service.GitService;
import io.aledep10.nomadsync.service.GitignoreService;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.service.VaultService;
import io.aledep10.nomadsync.util.FileUtil;
import io.aledep10.nomadsync.util.OsUtil;
import io.aledep10.nomadsync.util.TestUtil;
import io.aledep10.nomadsync.util.TestVault;
import org.junit.jupiter.api.*;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Main} private static handler methods.
 *
 * <p>All methods under test are private and accessed via reflection through
 * the {@link #invoke} helper. This approach avoids changing production visibility
 * while keeping test coverage granular at the handler level.</p>
 *
 * <p>{@link System#out} is redirected to {@link #outputStream} in {@link #setUp}
 * and restored in {@link #tearDown} — tests that assert on console output read
 * from {@code outputStream.toString()}.</p>
 *
 * <h2>Cross-platform paths</h2>
 * <p>Tests that construct paths use {@link #createPath(String...)} to build
 * OS-native separators, consistent with {@link Vault}'s internal normalisation.</p>
 *
 * <h2>Exit codes</h2>
 * <p>All {@code handleVault*}, {@code handleStatus}, and {@code handleConfig}
 * methods return an {@code int}: {@code 0} success, {@code 1} error, {@code 2}
 * no-op (e.g. no changes requested, user aborted a confirmation).</p>
 */
@DisplayName("Unit tests for Main")
class MainTest {

    static TestVault testVault;
    static LogService logService;

    VaultService vaultService;
    Properties properties;
    PrintStream originalOut;
    PrintStream originalErr;
    ByteArrayOutputStream outputStream;

    @BeforeAll
    static void prepareSharedState() throws IOException {
        testVault  = TestUtil.getTestVault("MainTest");
        logService = new LogService(TestUtil.forLogService(testVault,
                io.aledep10.nomadsync.logging.LogLevel.DEBUG), testVault.rootPath());
    }

    @BeforeEach
    void setUp() throws IOException {
        properties = new Properties();
        Properties vaultProperties = TestUtil.forVaultService(testVault);
        GitignoreService gitignoreService = new GitignoreService(logService);
        vaultService = new VaultService(vaultProperties, testVault.vaultPath(), gitignoreService, logService);

        originalOut = System.out;
        originalErr = System.err;
        outputStream = new ByteArrayOutputStream();
        PrintStream captured = new PrintStream(outputStream);
        System.setOut(captured);
        System.setErr(captured);
    }

    @AfterEach
    void tearDown() throws IOException {
        System.setOut(originalOut);
        System.setErr(originalErr);
        TestUtil.cleanup(testVault);
    }

    @AfterAll
    static void tearDownAll() {
        logService.close();
    }

    /**
     * Reflectively invokes a private static method on {@link Main}.
     *
     * @param methodName     name of the private static method
     * @param parameterTypes parameter types for method lookup
     * @param args           arguments to pass to the method
     * @return the method's return value, or {@code null} for {@code void} methods
     * @throws RuntimeException wrapping any reflection or invocation exception —
     *         when the invoked method throws a checked exception, the real cause
     *         is nested two levels down: {@code RuntimeException.getCause()} is
     *         an {@link InvocationTargetException}, whose own {@code getCause()}
     *         is the exception actually thrown by the production method.
     */
    private static Object invoke(String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = Main.class.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Builds an OS-native path string from the given segments, prefixing each
     * with {@link OsUtil#separator()}.
     *
     * @param parts path segments without leading or trailing separators
     * @return an OS-native path string
     */
    static String createPath(String... parts) {
        final StringBuilder buf = new StringBuilder();
        Arrays.asList(parts).forEach(part -> buf.append(OsUtil.separator()).append(part));
        return buf.toString();
    }

    // ── operationToEventType ──────────────────────────────────────────────────

    @Nested
    @DisplayName("operationToEventType")
    class OperationToEventTypeTests {

        @Test
        @DisplayName("pull returns PULL_LOGON")
        void pull_returnsPullLogon() {
            EventType eventType = (EventType) invoke("operationToEventType",
                    new Class<?>[]{String.class, LogService.class}, "pull", logService);
            assertThat(eventType).isEqualTo(EventType.PULL_LOGON);
        }

        @Test
        @DisplayName("autosave returns null")
        void autosave_returnsNull() {
            Object eventType = invoke("operationToEventType",
                    new Class<?>[]{String.class, LogService.class}, "autosave", logService);
            assertThat(eventType).isNull();
        }

        @Test
        @DisplayName("unknown operation returns null")
        void unknown_returnsNull() {
            Object eventType = invoke("operationToEventType",
                    new Class<?>[]{String.class, LogService.class}, "unknown", logService);
            assertThat(eventType).isNull();
        }
    }

    // ── applyGitFlagsToVault ──────────────────────────────────────────────────

    @Nested
    @DisplayName("applyGitFlagsToVault")
    class ApplyGitFlagsToVaultTests {

        @Test
        @DisplayName("applies all known git flags to vault")
        void applyGitFlagsToVault_appliesGitFields() {
            Vault vault = new Vault("id", "owner", "name", createPath("path"));
            Map<String, String> gitFlags = new LinkedHashMap<>();
            gitFlags.put("git.name",     "Alice");
            gitFlags.put("git.email",    "alice@example.com");
            gitFlags.put("git.username", "alice-gh");
            gitFlags.put("git.token",    "token123");
            gitFlags.put("git.branch",   "develop");
            gitFlags.put("git.remote",   "upstream");

            invoke("applyGitFlagsToVault",
                    new Class<?>[]{Map.class, Vault.class, LogService.class},
                    gitFlags, vault, logService);

            assertThat(vault.getGitName()).isEqualTo("Alice");
            assertThat(vault.getGitEmail()).isEqualTo("alice@example.com");
            assertThat(vault.getGitUsername()).isEqualTo("alice-gh");
            assertThat(vault.getGitToken()).isEqualTo("token123");
            assertThat(vault.getGitBranch()).isEqualTo("develop");
            assertThat(vault.getGitRemote()).isEqualTo("upstream");
        }

        @Test
        @DisplayName("ignores unknown git flags leaving vault fields unchanged")
        void applyGitFlagsToVault_ignoresUnknownKeys() {
            Vault vault = new Vault("id", "owner", "name", createPath("path"));
            Map<String, String> gitFlags = new LinkedHashMap<>();
            gitFlags.put("git.unknown", "value");

            invoke("applyGitFlagsToVault",
                    new Class<?>[]{Map.class, Vault.class, LogService.class},
                    gitFlags, vault, logService);

            assertThat(vault.getName()).isEqualTo("name");
            assertThat(vault.getGitName()).isNull();
            assertThat(vault.getGitRemote()).isNull();
        }
    }

    // ── resolveVaultFlag ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveVaultFlag")
    class ResolveVaultFlagTests {

        @Test
        @DisplayName("resolves vault by exact repo slug")
        void resolvesVaultByExactRepoSlug() {
            Vault vault = new Vault("id", "owner", "name", createPath());
            Vault resolved = (Vault) invoke("resolveVaultFlag",
                    new Class<?>[]{String.class, List.class},
                    "owner/name", List.of(vault));

            assertThat(resolved).isSameAs(vault);
        }

        @Test
        @DisplayName("resolves vault by unambiguous name")
        void resolvesVaultByName() {
            Vault vault = new Vault("id", "owner", "name", createPath());
            Vault resolved = (Vault) invoke("resolveVaultFlag",
                    new Class<?>[]{String.class, List.class},
                    "name", List.of(vault));

            assertThat(resolved).isSameAs(vault);
        }

        @Test
        @DisplayName("throws VaultAmbiguousException when name matches multiple vaults")
        void ambiguousName_throwsAmbiguous() {
            Vault vault1 = new Vault("id1", "owner1", "name", createPath("path1"));
            Vault vault2 = new Vault("id2", "owner2", "name", createPath("path2"));

            assertThatThrownBy(() -> invoke("resolveVaultFlag",
                    new Class<?>[]{String.class, List.class},
                    "name", List.of(vault1, vault2)))
                    .hasCauseInstanceOf(InvocationTargetException.class)
                    .extracting(Throwable::getCause)
                    .extracting(Throwable::getCause)
                    .isInstanceOf(VaultAmbiguousException.class);
        }

        @Test
        @DisplayName("throws VaultNotFoundException when no vault matches")
        void noMatch_throwsNotFound() {
            Vault vault = new Vault("id", "owner", "name", createPath());

            assertThatThrownBy(() -> invoke("resolveVaultFlag",
                    new Class<?>[]{String.class, List.class},
                    "nonexistent", List.of(vault)))
                    .hasCauseInstanceOf(InvocationTargetException.class)
                    .extracting(Throwable::getCause)
                    .extracting(Throwable::getCause)
                    .isInstanceOf(VaultNotFoundException.class);
        }
    }

    // ── hasUnknownFlags ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("hasUnknownFlags")
    class HasUnknownFlagsTests {

        @Test
        @DisplayName("returns false when all flags are known")
        void allKnown_returnsFalse() {
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "Alice");
            flags.put("name", "my-vault");

            boolean result = (boolean) invoke("hasUnknownFlags",
                    new Class<?>[]{Map.class, java.util.Set.class, String.class, LogService.class},
                    flags, java.util.Set.of("owner", "name", "path"), "handleVaultAdd", logService);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns true and logs error when unknown flag is present")
        void unknownFlag_returnsTrue() {
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("defaults", "");   // not in FLAGS_VAULT_UPDATE

            boolean result = (boolean) invoke("hasUnknownFlags",
                    new Class<?>[]{Map.class, java.util.Set.class, String.class, LogService.class},
                    flags, java.util.Set.of("vault", "owner", "name"), "handleVaultUpdate",
                    logService);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("always ignores internal 'sub' key")
        void subKey_isAlwaysIgnored() {
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("sub", "list");   // injected by parser — must never be reported

            boolean result = (boolean) invoke("hasUnknownFlags",
                    new Class<?>[]{Map.class, java.util.Set.class, String.class, LogService.class},
                    flags, java.util.Set.of(), "handleVaultList", logService);

            assertThat(result).isFalse();
        }
    }

    // ── handleVaultCreate ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleVaultCreate")
    class HandleVaultCreateTests {

        @Test
        @DisplayName("returns 1 when required flags are missing")
        void missingFlags_returnsError() {
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);

            int result = (int) invoke("handleVaultCreate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    new LinkedHashMap<>(), List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 when unknown flag is present")
        void unknownFlag_returnsError() throws IOException {
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            Path tempDir = Files.createTempDirectory("vault-create-unknownflag");

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "owner");
            flags.put("name", "name");
            flags.put("path", tempDir.toString());
            flags.put("defaults", ""); // not in FLAGS_VAULT_CREATE

            int result = (int) invoke("handleVaultCreate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 2 when repoSlug is already registered, without touching the filesystem")
        void repoSlugAlreadyRegistered_returnsNoOp() throws Exception {
            Vault existing = new Vault("id", "owner", "name", createPath("elsewhere"));
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "owner");
            flags.put("name", "name");
            flags.put("path", createPath("some", "new", "path"));

            int result = (int) invoke("handleVaultCreate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(existing), vaultService, gitService, logService);

            assertThat(result).isEqualTo(2);
            verify(gitService, times(0)).init(any());
            verify(vaultService, times(0)).create(any(), any(), any());
        }

        @Test
        @DisplayName("returns 2 when path already contains a .git directory")
        void pathAlreadyGitRepo_returnsNoOp() throws Exception {
            Path tempRepo = Files.createTempDirectory("vault-create-existing-git");
            Files.createDirectories(tempRepo.resolve(".git"));
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "owner");
            flags.put("name", "name");
            flags.put("path", tempRepo.toString());

            int result = (int) invoke("handleVaultCreate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(2);
            verify(gitService, times(0)).init(any());
            verify(vaultService, times(0)).create(any(), any(), any());
        }

        @Test
        @DisplayName("returns 1 when path exists, is non-empty, and has no .git directory")
        void pathExistsNonEmptyNoGit_returnsError() throws Exception {
            Path tempDir = Files.createTempDirectory("vault-create-nonempty");
            Files.createFile(tempDir.resolve("some-file.txt"));
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "owner");
            flags.put("name", "name");
            flags.put("path", tempDir.toString());

            int result = (int) invoke("handleVaultCreate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
            verify(gitService, times(0)).init(any());
            verify(vaultService, times(0)).create(any(), any(), any());
        }

        @Test
        @DisplayName("creates directory, initialises the repo, then registers it — in that order")
        void pathAbsent_initBeforeCreate() throws Exception {
            Path parentDir = Files.createTempDirectory("vault-create-parent");
            Path targetPath = parentDir.resolve("brand-new-vault");
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            Vault vault = new Vault("id", "owner", "name", targetPath.toString());
            doReturn(vault).when(vaultService).create(eq("owner"), eq("name"), eq(targetPath.toString()));
            doNothing().when(gitService).init(any(Vault.class));
            doNothing().when(gitService).bootstrapVault(any(Vault.class));

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "owner");
            flags.put("name", "name");
            flags.put("path", targetPath.toString());

            int result = (int) invoke("handleVaultCreate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(0);
            assertThat(targetPath.toFile()).exists().isDirectory();

            // Explicit order guarantee: init() must run before create() —
            // see the confirmed rationale (orphaned .git is a safer failure mode
            // than a registered vault pointing at a non-repository path).
            InOrder inOrder = inOrder(gitService, vaultService);
            inOrder.verify(gitService, times(1)).init(any(Vault.class));
            inOrder.verify(vaultService, times(1)).create(eq("owner"), eq("name"), eq(targetPath.toString()));
            verify(gitService, times(1)).bootstrapVault(vault);
        }

        @Test
        @DisplayName("initialises and registers when path exists and is empty")
        void pathExistsEmpty_initAndRegisters() throws Exception {
            Path tempDir = Files.createTempDirectory("vault-create-empty");
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            Vault vault = new Vault("id", "owner", "name", tempDir.toString());
            doReturn(vault).when(vaultService).create(eq("owner"), eq("name"), eq(tempDir.toString()));
            doNothing().when(gitService).init(any(Vault.class));
            doNothing().when(gitService).bootstrapVault(any(Vault.class));

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "owner");
            flags.put("name", "name");
            flags.put("path", tempDir.toString());

            int result = (int) invoke("handleVaultCreate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(0);
            verify(gitService, times(1)).init(any(Vault.class));
            verify(vaultService, times(1)).create(eq("owner"), eq("name"), eq(tempDir.toString()));
        }

        @Test
        @DisplayName("returns 1 when gitService.init throws GitException, without attempting registration")
        void initThrowsGitException_returnsError() throws Exception {
            Path tempDir = Files.createTempDirectory("vault-create-init-fails");
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            doThrow(new GitException("git init failed")).when(gitService).init(any(Vault.class));

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "owner");
            flags.put("name", "name");
            flags.put("path", tempDir.toString());

            int result = (int) invoke("handleVaultCreate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
            verify(vaultService, times(0)).create(any(), any(), any());
        }

        @Test
        @DisplayName("returns 1 when vaultService.create throws VaultException after successful init")
        void createThrowsVaultException_returnsError() throws Exception {
            Path tempDir = Files.createTempDirectory("vault-create-register-fails");
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            doNothing().when(gitService).init(any(Vault.class));
            doThrow(new VaultException("duplicated repoSlug: owner/name"))
                    .when(vaultService).create(any(), any(), any());

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "owner");
            flags.put("name", "name");
            flags.put("path", tempDir.toString());

            int result = (int) invoke("handleVaultCreate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
            // NOTE: no rollback assertion here — a partially initialised, orphaned
            // .git/ is the confirmed, accepted failure mode; no automatic cleanup.
            verify(gitService, times(1)).init(any(Vault.class));
        }
    }

    // ── handleVaultAdd ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleVaultAdd")
    class HandleVaultAddTests {

        @Test
        @DisplayName("returns 1 when required flags are missing")
        void missingFlags_returnsError() {
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);

            int result = (int) invoke("handleVaultAdd",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    new LinkedHashMap<>(), List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 when unknown flag is present")
        void unknownFlag_returnsError() {
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "owner");
            flags.put("name", "name");
            flags.put("path", createPath("some", "path"));
            flags.put("defaults", ""); // not in FLAGS_VAULT_ADD

            int result = (int) invoke("handleVaultAdd",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 2 when repoSlug is already registered, without touching the filesystem or vaultService")
        void repoSlugAlreadyRegistered_returnsNoOp() {
            Vault existing = new Vault("id", "owner", "name", createPath("elsewhere"));
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "owner");
            flags.put("name", "name");
            flags.put("path", createPath("nonexistent", "path")); // would fail path check too — pre-check must win first

            int result = (int) invoke("handleVaultAdd",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(existing), vaultService, gitService, logService);

            assertThat(result).isEqualTo(2);
            verifyNoInteractions(vaultService);
            verifyNoInteractions(gitService);
        }

        @Test
        @DisplayName("returns 1 when path does not exist on filesystem")
        void nonExistentPath_returnsError() {
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "owner");
            flags.put("name", "name");
            flags.put("path", createPath("nonexistent", "path", "that", "does", "not", "exist"));

            int result = (int) invoke("handleVaultAdd",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 when path exists but contains no .git directory")
        void pathWithoutGitDir_returnsError() throws IOException {
            Path tempDir = Files.createTempDirectory("vault-add-nogit");
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "owner");
            flags.put("name", "name");
            flags.put("path", tempDir.toString());

            int result = (int) invoke("handleVaultAdd",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 when vaultService.create throws VaultException (e.g. duplicated path)")
        void createThrowsVaultException_returnsError() throws Exception {
            // repoSlug collisions are now caught by the pre-check before this point —
            // this test covers the residual case where vaultService.create() still
            // throws for a reason the pre-check does not cover (e.g. path collision).
            Path tempRepo = Files.createTempDirectory("vault-add-duplicate");
            Files.createDirectories(tempRepo.resolve(".git"));
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            when(vaultService.create(any(), any(), any()))
                    .thenThrow(new VaultException("duplicated path: " + tempRepo));

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "owner");
            flags.put("name", "name");
            flags.put("path", tempRepo.toString());

            int result = (int) invoke("handleVaultAdd",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 0 and adds vault when flags are valid and path is a git repository")
        void validFlags_addsVault() throws Exception {
            Path tempRepo = Files.createTempDirectory("vault-add-test");
            Files.createDirectories(tempRepo.resolve(".git"));
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            Vault vault = new Vault("id", "owner", "name", tempRepo.toString());
            doReturn(vault).when(vaultService).create(eq("owner"), eq("name"), eq(tempRepo.toString()));
            doNothing().when(gitService).bootstrapVault(vault);

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "owner");
            flags.put("name", "name");
            flags.put("path", tempRepo.toString());

            int result = (int) invoke("handleVaultAdd",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(0);
            verify(vaultService, times(1)).create(eq("owner"), eq("name"), eq(tempRepo.toString()));
            verify(gitService, times(1)).bootstrapVault(vault);
        }
    }

    // ── handleVaultUpdate ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleVaultUpdate")
    class HandleVaultUpdateTests {

        @Test
        @DisplayName("returns 1 when --vault flag is missing")
        void missingVaultFlag_returnsError() {
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);

            int result = (int) invoke("handleVaultUpdate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    new LinkedHashMap<>(), List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 when unknown flag is present")
        void unknownFlag_returnsError() {
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");
            flags.put("defaults", ""); // not in FLAGS_VAULT_UPDATE

            int result = (int) invoke("handleVaultUpdate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 2 and skips update when no changes are requested")
        void noChanges_returnsNoOp() throws VaultException {
            Vault vault = new Vault("id", "owner", "name", createPath());
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");

            int result = (int) invoke("handleVaultUpdate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(vault), vaultService, gitService, logService);

            assertThat(result).isEqualTo(2);
            verify(vaultService, times(0)).update(any());
        }

        @Test
        @DisplayName("returns 0, persists updates and bootstraps vault when structural changes are applied")
        void changes_persistAndBootstrap() throws Exception {
            Vault vault = new Vault("id", "owner", "name", createPath());
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            doNothing().when(vaultService).update(any());
            doNothing().when(gitService).bootstrapVault(any());

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault",      "owner/name");
            flags.put("owner",      "owner2");
            flags.put("name",       "name2");
            flags.put("path",       createPath("new-path"));
            flags.put("git.branch", "develop");
            flags.put("git.remote", "upstream");
            flags.put("git.name",   "Alice");
            flags.put("git.email",  "alice@example.com");

            int result = (int) invoke("handleVaultUpdate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(vault), vaultService, gitService, logService);

            assertThat(result).isEqualTo(0);
            verify(vaultService, times(1)).update(any(Vault.class));
            verify(gitService, times(1)).bootstrapVault(any(Vault.class));
        }

        @Test
        @DisplayName("returns 0 and bootstraps vault when only git credentials change")
        void credentialsOnlyChange_bootstraps() throws Exception {
            Vault vault = new Vault("id", "owner", "name", createPath());
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            doNothing().when(vaultService).update(any());
            doNothing().when(gitService).bootstrapVault(any());

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault",     "owner/name");
            flags.put("git.token", "new-token");

            int result = (int) invoke("handleVaultUpdate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(vault), vaultService, gitService, logService);

            assertThat(result).isEqualTo(0);
            verify(vaultService, times(1)).update(any(Vault.class));
            verify(gitService, times(1)).bootstrapVault(any(Vault.class));
        }
    }

    // ── handleVaultRemove ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleVaultRemove")
    class HandleVaultRemoveTests {

        @Test
        @DisplayName("returns 1 when --vault flag is missing")
        void missingVaultFlag_returnsError() {
            VaultService vaultService = mock(VaultService.class);

            int result = (int) invoke("handleVaultRemove",
                    new Class<?>[]{Map.class, List.class, VaultService.class, LogService.class},
                    new LinkedHashMap<>(), List.of(), vaultService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 when unknown flag is present")
        void unknownFlag_returnsError() {
            VaultService vaultService = mock(VaultService.class);
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault",    "owner/name");
            flags.put("defaults", ""); // not in FLAGS_VAULT_REMOVE

            int result = (int) invoke("handleVaultRemove",
                    new Class<?>[]{Map.class, List.class, VaultService.class, LogService.class},
                    flags, List.of(), vaultService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 when vault is not found")
        void vaultNotFound_returnsError() {
            VaultService vaultService = mock(VaultService.class);
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/nonexistent");

            int result = (int) invoke("handleVaultRemove",
                    new Class<?>[]{Map.class, List.class, VaultService.class, LogService.class},
                    flags, List.of(), vaultService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 2 and skips deletion when user aborts")
        void abortRemoval_returnsNoOp() throws VaultException {
            Vault vault = new Vault("id", "owner", "name", createPath());
            VaultService vaultService = mock(VaultService.class);
            System.setIn(new ByteArrayInputStream("n\n".getBytes()));
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");

            int result = (int) invoke("handleVaultRemove",
                    new Class<?>[]{Map.class, List.class, VaultService.class, LogService.class},
                    flags, List.of(vault), vaultService, logService);

            assertThat(result).isEqualTo(2);
            verify(vaultService, times(0)).delete(any());
        }

        @Test
        @DisplayName("returns 0 and removes vault when user confirms with y")
        void confirmRemoval_deletesVault() throws Exception {
            Vault vault = new Vault("id", "owner", "name", createPath());
            VaultService vaultService = mock(VaultService.class);
            doNothing().when(vaultService).delete(eq("id"));
            System.setIn(new ByteArrayInputStream("y\n".getBytes()));
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");

            int result = (int) invoke("handleVaultRemove",
                    new Class<?>[]{Map.class, List.class, VaultService.class, LogService.class},
                    flags, List.of(vault), vaultService, logService);

            assertThat(result).isEqualTo(0);
            verify(vaultService, times(1)).delete(eq("id"));
        }
    }

    // ── handleVaultRelocate ────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleVaultRelocate")
    class HandleVaultRelocateTests {

        @Test
        @DisplayName("returns 1 when --vault is missing")
        void missingVaultFlag_returnsError() {
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);

            int result = (int) invoke("handleVaultRelocate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    new LinkedHashMap<>(), List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 when unknown flag is present")
        void unknownFlag_returnsError() {
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            Vault vault = new Vault("id", "owner", "name", createPath("current"));

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");
            flags.put("owner", "neworg");
            flags.put("defaults", ""); // not in FLAGS_VAULT_RELOCATE

            int result = (int) invoke("handleVaultRelocate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(vault), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 when vault cannot be resolved")
        void vaultNotFound_returnsError() {
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/nonexistent");
            flags.put("owner", "neworg");

            int result = (int) invoke("handleVaultRelocate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 2 when nothing is requested at all (pure no-op)")
        void nothingRequested_returnsNoOp() throws Exception {
            Vault vault = new Vault("id", "owner", "name", TestUtil.absolute(createPath("current")));
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");
            // no owner/name/path, no git.* — nothing requested at all

            int result = (int) invoke("handleVaultRelocate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(vault), vaultService, gitService, logService);

            assertThat(result).isEqualTo(2);
            verify(gitService, times(0)).reset(any());
            verify(vaultService, times(0)).update(any());
            verify(vaultService, times(0)).makeVaultSnapshot(any());
        }

        @Test
        @DisplayName("returns 1 when owner/name/path are unchanged but --git.* is requested (blocked, not no-op)")
        void credentialOnlyChange_returnsError() throws Exception {
            Vault vault = new Vault("id", "owner", "name", TestUtil.absolute(createPath("current")));
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");
            flags.put("owner", "owner"); // same as current — no structural change
            flags.put("git.token", "new-token"); // credentials only

            int result = (int) invoke("handleVaultRelocate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(vault), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
            verify(gitService, times(0)).reset(any());
            verify(vaultService, times(0)).update(any());
        }

        @Test
        @DisplayName("returns 2 and performs no destructive action when user declines confirmation")
        void userDeclines_returnsNoOp() throws Exception {
            Vault vault = new Vault("id", "owner", "name", createPath("current"));
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            System.setIn(new ByteArrayInputStream("n\n".getBytes()));

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");
            flags.put("owner", "neworg");

            int result = (int) invoke("handleVaultRelocate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(vault), vaultService, gitService, logService);

            assertThat(result).isEqualTo(2);
            verify(gitService, times(0)).reset(any());
            verify(vaultService, times(0)).makeVaultSnapshot(any());
            verify(vaultService, times(0)).update(any());
        }

        @Test
        @DisplayName("--force bypasses the confirmation prompt entirely")
        void forceFlag_skipsConfirmation() throws Exception {
            Path tempDir = Files.createTempDirectory("vault-relocate-force");
            Vault vault = new Vault("id", "owner", "name", tempDir.toString());
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            doNothing().when(gitService).reset(any(Vault.class));
            doNothing().when(gitService).bootstrapVault(any(Vault.class));
            doNothing().when(vaultService).update(any(Vault.class));
            // System.in deliberately left unset — if the handler tried to read a
            // confirmation, this test would hang or fail, proving --force truly
            // bypasses the prompt rather than reading a default answer.

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");
            flags.put("owner", "neworg");
            flags.put("force", "");

            int result = (int) invoke("handleVaultRelocate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(vault), vaultService, gitService, logService);

            assertThat(result).isEqualTo(0);
            verify(gitService, times(1)).reset(vault);
        }

        @Test
        @DisplayName("returns 1 when snapshot fails, before any destructive action")
        void snapshotFails_returnsError() throws Exception {
            Vault vault = new Vault("id", "owner", "name", createPath("current"));
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            doThrow(new VaultException("snapshot failed"))
                    .when(vaultService).makeVaultSnapshot(any());

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");
            flags.put("owner", "neworg");
            flags.put("force", "");

            int result = (int) invoke("handleVaultRelocate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(vault), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
            verify(gitService, times(0)).reset(any());
        }

        @Test
        @DisplayName("returns 1 when git reset fails, without persisting or moving anything")
        void gitResetFails_returnsError() throws Exception {
            Vault vault = new Vault("id", "owner", "name", createPath("current"));
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            doThrow(new GitException("reset failed")).when(gitService).reset(any(Vault.class));

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");
            flags.put("owner", "neworg");
            flags.put("force", "");

            int result = (int) invoke("handleVaultRelocate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(vault), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
            verify(vaultService, times(0)).update(any());
        }

        @Test
        @DisplayName("relocates in place (no path change) without touching the filesystem move")
        void samePathOwnerChange_relocatesInPlace() throws Exception {
            Path tempDir = Files.createTempDirectory("vault-relocate-samepath");
            Vault vault = new Vault("id", "owner", "name", tempDir.toString());
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            doNothing().when(gitService).reset(any(Vault.class));
            doNothing().when(gitService).bootstrapVault(any(Vault.class));
            doNothing().when(vaultService).update(any(Vault.class));

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");
            flags.put("owner", "neworg"); // structural change: owner only
            flags.put("force", "");

            int result = (int) invoke("handleVaultRelocate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(vault), vaultService, gitService, logService);

            assertThat(result).isEqualTo(0);
            // original directory must still exist — no move was requested
            assertThat(tempDir.toFile()).exists().isDirectory();
            verify(gitService, times(1)).reset(vault);
        }

        @Test
        @DisplayName("copies to the new path and removes the original when --path differs")
        void pathChange_copiesThenRemovesOriginal() throws Exception {
            Path sourceDir = Files.createTempDirectory("vault-relocate-source");
            Path marker = sourceDir.resolve("note.md");
            Files.writeString(marker, "hello vault");
            Path parentForTarget = Files.createTempDirectory("vault-relocate-target-parent");
            Path targetDir = parentForTarget.resolve("moved-vault");

            Vault vault = new Vault("id", "owner", "name", sourceDir.toString());
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            doNothing().when(gitService).reset(any(Vault.class));
            doNothing().when(gitService).bootstrapVault(any(Vault.class));
            doNothing().when(vaultService).update(any(Vault.class));

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");
            flags.put("path", targetDir.toString());
            flags.put("force", "");

            int result = (int) invoke("handleVaultRelocate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(vault), vaultService, gitService, logService);

            assertThat(result).isEqualTo(0);
            assertThat(targetDir.resolve("note.md")).exists().hasContent("hello vault");
            assertThat(sourceDir.toFile()).doesNotExist();
        }

        @Test
        @DisplayName("returns 1 when vaultService.update fails after a successful reset")
        void updateFails_returnsError() throws Exception {
            Path tempDir = Files.createTempDirectory("vault-relocate-update-fails");
            Vault vault = new Vault("id", "owner", "name", tempDir.toString());
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            doNothing().when(gitService).reset(any(Vault.class));
            doThrow(new VaultException("persistence failed")).when(vaultService).update(any(Vault.class));

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");
            flags.put("owner", "neworg");
            flags.put("force", "");

            int result = (int) invoke("handleVaultRelocate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(vault), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
            verify(gitService, times(0)).bootstrapVault(any());
        }

        @Test
        @DisplayName("returns 1 when bootstrapVault fails after a successful update")
        void bootstrapVaultFails_returnsError() throws Exception {
            Path tempDir = Files.createTempDirectory("vault-relocate-bootstrap-fails");
            Vault vault = new Vault("id", "owner", "name", tempDir.toString());
            VaultService vaultService = mock(VaultService.class);
            GitService gitService = mock(GitService.class);
            doNothing().when(gitService).reset(any(Vault.class));
            doNothing().when(vaultService).update(any(Vault.class));
            doThrow(new GitException("bootstrap failed")).when(gitService).bootstrapVault(any(Vault.class));

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");
            flags.put("owner", "neworg");
            flags.put("force", "");

            int result = (int) invoke("handleVaultRelocate",
                    new Class<?>[]{Map.class, List.class, VaultService.class,
                            GitService.class, LogService.class},
                    flags, List.of(vault), vaultService, gitService, logService);

            assertThat(result).isEqualTo(1);
        }
    }

    // ── handleVaultList ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleVaultList")
    class HandleVaultListTests {

        @Test
        @DisplayName("returns 1 when vault list is null")
        void nullVaultList_returnsError() {
            // Empty flags — hasUnknownFlags passes; the null check is what's under test here.
            Map<String, String> flags = new LinkedHashMap<>();

            int result = (int) invoke("handleVaultList",
                    new Class<?>[]{Map.class, List.class, LogService.class},
                    flags, null, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 0 when no vaults are registered")
        void emptyVaults_returnsSuccess() {
            Map<String, String> flags = new LinkedHashMap<>();

            int result = (int) invoke("handleVaultList",
                    new Class<?>[]{Map.class, List.class, LogService.class},
                    flags, List.of(), logService);

            assertThat(result).isEqualTo(0);
        }

        @Test
        @DisplayName("returns 0 when vault list is non-empty")
        void nonEmptyVaults_returnsSuccess() throws Exception {
            Map<String, String> flags = new LinkedHashMap<>();
            Path vaultContent = Files.createTempDirectory("nomadsync-list-target");
            Vault vault = vaultService.create("Alice", "list-target", vaultContent.toString());

            int result = (int) invoke("handleVaultList",
                    new Class<?>[]{Map.class, List.class, LogService.class},
                    flags, List.of(vault), logService);

            assertThat(result).isEqualTo(0);

            FileUtil.deleteRecursively(vaultContent);
        }

        @Test
        @DisplayName("returns 1 when unknown flag is present")
        void unknownFlag_returnsError() {
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "Alice"); // not yet in FLAGS_VAULT_LIST

            int result = (int) invoke("handleVaultList",
                    new Class<?>[]{Map.class, List.class, LogService.class},
                    flags, List.of(), logService);

            assertThat(result).isEqualTo(1);
        }
    }

    // ── handleVaultShow ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleVaultShow")
    class HandleVaultShowTests {

        @Test
        @DisplayName("returns 1 when --vault flag is missing")
        void missingVaultFlag_returnsError() {
            GitService gitService = mock(GitService.class);

            int result = (int) invoke("handleVaultShow",
                    new Class<?>[]{Map.class, List.class, int.class, GitService.class, LogService.class},
                    new LinkedHashMap<>(), List.of(), 5, gitService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 when unknown flag is present")
        void unknownFlag_returnsError() {
            GitService gitService = mock(GitService.class);
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault",   "owner/name");
            flags.put("format",  "json"); // not in FLAGS_VAULT_SHOW yet

            int result = (int) invoke("handleVaultShow",
                    new Class<?>[]{Map.class, List.class, int.class, GitService.class, LogService.class},
                    flags, List.of(), 5, gitService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 when vault is not found")
        void vaultNotFound_returnsError() {
            GitService gitService = mock(GitService.class);
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/nonexistent");

            int result = (int) invoke("handleVaultShow",
                    new Class<?>[]{Map.class, List.class, int.class, GitService.class, LogService.class},
                    flags, List.of(), 5, gitService, logService);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 0, prints vault details and git status summary")
        void showsVaultDetails() throws Exception {
            Vault vault = new Vault("id", "owner", "name", createPath());
            GitService gitService = mock(GitService.class);
            doReturn("modified file.txt").when(gitService).statusShort(eq(vault), eq(5));
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "owner/name");

            int result = (int) invoke("handleVaultShow",
                    new Class<?>[]{Map.class, List.class, int.class, GitService.class, LogService.class},
                    flags, List.of(vault), 5, gitService, logService);

            assertThat(result).isEqualTo(0);
            assertThat(outputStream.toString())
                    .contains("Vault:")
                    .contains("owner/name")
                    .contains("Status:");
            verify(gitService, times(1)).statusShort(eq(vault), eq(5));
        }

        @Test
        @DisplayName("returns 0 and shows git overrides when --defaults is present")
        void showsGitOverridesWithDefaults() throws Exception {
            // Vault with an explicit override on git.branch: getGitBranch() != null,
            // so the "Git Branch:" line is always printed regardless of --defaults.
            Vault vault = new Vault("id", "owner", "name", createPath());
            vault.setGitBranch("main");

            // any(Vault.class) is used instead of eq(vault) because handleVaultShow
            // resolves the vault via resolveVaultFlag before calling statusShort —
            // same instance, but eq() relies on equals(), which Vault may not override.
            GitService gitService = mock(GitService.class);
            doReturn("").when(gitService).statusShort(any(Vault.class), anyInt());

            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault",    "owner/name");
            flags.put("defaults", "");

            // A fresh local LogService is required here: the static @BeforeAll
            // logService captured the original System.out before setUp() redirected
            // it to outputStream, so it can't see this test's captured output.
            Properties localProps = new Properties();
            localProps.setProperty("log.writers", "console");
            localProps.setProperty("log.level", "DEBUG");
            LogService localLogService = new LogService(localProps, testVault.rootPath());

            int result = (int) invoke("handleVaultShow",
                    new Class<?>[]{Map.class, List.class, int.class, GitService.class, LogService.class},
                    flags, List.of(vault), 5, gitService, localLogService);

            assertThat(result).isEqualTo(0);
            assertThat(outputStream.toString()).contains("Git Branch:");
        }
    }

    // ── hasBlankRequiredFlags ──────────────────────────────────────────────────

    @Nested
    @DisplayName("hasBlankRequiredFlags")
    class HasBlankRequiredFlagsTests {

        @Test
        @DisplayName("returns false when all required keys are present and non-blank")
        void allPresent_returnsFalse() {
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "Alice");
            flags.put("name", "vault-name");
            flags.put("path", createPath("some", "path"));

            boolean result = (boolean) invoke("hasBlankRequiredFlags",
                    new Class<?>[]{Map.class, java.util.Set.class, String.class, LogService.class},
                    flags, java.util.Set.of("owner", "name", "path"), "handleVaultAdd", logService);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns true when a required key is entirely absent")
        void missingKey_returnsTrue() {
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "Alice");
            // "name" and "path" missing entirely

            boolean result = (boolean) invoke("hasBlankRequiredFlags",
                    new Class<?>[]{Map.class, java.util.Set.class, String.class, LogService.class},
                    flags, java.util.Set.of("owner", "name", "path"), "handleVaultAdd", logService);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns true when a required key is present but blank — same violation as absent")
        void blankValue_returnsTrue() {
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "Alice");
            flags.put("name", "");
            flags.put("path", createPath("some", "path"));

            boolean result = (boolean) invoke("hasBlankRequiredFlags",
                    new Class<?>[]{Map.class, java.util.Set.class, String.class, LogService.class},
                    flags, java.util.Set.of("owner", "name", "path"), "handleVaultAdd", logService);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("treats a whitespace-only value the same as blank")
        void whitespaceOnlyValue_returnsTrue() {
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("vault", "   ");

            boolean result = (boolean) invoke("hasBlankRequiredFlags",
                    new Class<?>[]{Map.class, java.util.Set.class, String.class, LogService.class},
                    flags, java.util.Set.of("vault"), "handleVaultUpdate", logService);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("uses the known syntax hint for --vault instead of a generic message")
        void missingVault_usesKnownSyntaxHint() {
            LogService localLogService = freshConsoleLogService();
            Map<String, String> flags = new LinkedHashMap<>();

            boolean result = (boolean) invoke("hasBlankRequiredFlags",
                    new Class<?>[]{Map.class, java.util.Set.class, String.class, LogService.class},
                    flags, java.util.Set.of("vault"), "handleVaultUpdate", localLogService);

            assertThat(result).isTrue();
            assertThat(outputStream.toString())
                    .contains("handleVaultUpdate: requires --vault=<name|owner/name>");
        }

        @Test
        @DisplayName("falls back to a generic '--key=<value>' hint for keys without a known syntax")
        void missingKeyWithoutHint_usesGenericFormat() {
            LogService localLogService = freshConsoleLogService();
            Map<String, String> flags = new LinkedHashMap<>();

            boolean result = (boolean) invoke("hasBlankRequiredFlags",
                    new Class<?>[]{Map.class, java.util.Set.class, String.class, LogService.class},
                    flags, java.util.Set.of("owner"), "handleVaultAdd", localLogService);

            assertThat(result).isTrue();
            assertThat(outputStream.toString())
                    .contains("handleVaultAdd: requires --owner=<value>");
        }

        @Test
        @DisplayName("logs one line per invalid key when multiple required keys are missing or blank")
        void multipleInvalidKeys_logsOneLinePerKey() {
            LogService localLogService = freshConsoleLogService();
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("path", ""); // present but blank
            // "owner" and "name" entirely absent

            boolean result = (boolean) invoke("hasBlankRequiredFlags",
                    new Class<?>[]{Map.class, java.util.Set.class, String.class, LogService.class},
                    flags, java.util.Set.of("owner", "name", "path"), "handleVaultAdd", localLogService);

            assertThat(result).isTrue();
            String output = outputStream.toString();
            assertThat(output).contains("handleVaultAdd: requires --owner=<value>");
            assertThat(output).contains("handleVaultAdd: requires --name=<value>");
            assertThat(output).contains("handleVaultAdd: requires --path=<value>");
        }

        @Test
        @DisplayName("returns false when the required set is empty — no requirement to violate")
        void emptyRequiredSet_returnsFalse() {
            Map<String, String> flags = new LinkedHashMap<>();

            boolean result = (boolean) invoke("hasBlankRequiredFlags",
                    new Class<?>[]{Map.class, java.util.Set.class, String.class, LogService.class},
                    flags, java.util.Set.of(), "handleVaultList", logService);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("ignores flags not part of the required set, even if blank — "
                + "this is what allows --git.token= to legitimately clear an override")
        void nonRequiredBlankFlag_isIgnored() {
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("owner", "Alice");
            flags.put("git.token", ""); // blank, but not in requiredKeys

            boolean result = (boolean) invoke("hasBlankRequiredFlags",
                    new Class<?>[]{Map.class, java.util.Set.class, String.class, LogService.class},
                    flags, java.util.Set.of("owner"), "handleVaultUpdate", logService);

            assertThat(result).isFalse();
        }

        private LogService freshConsoleLogService() {
            Properties localProps = new Properties();
            localProps.setProperty("log.writers", "console");
            localProps.setProperty("log.level", "DEBUG");
            return new LogService(localProps, testVault.rootPath());
        }
    }
}