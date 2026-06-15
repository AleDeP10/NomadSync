package io.aledep10.nomadsync.orchestrator;

import io.aledep10.nomadsync.exception.GitException;
import io.aledep10.nomadsync.exception.NetworkException;
import io.aledep10.nomadsync.exception.VaultException;
import io.aledep10.nomadsync.hook.NotificationHook;
import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.service.GitService;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.util.TestUtil;
import io.aledep10.nomadsync.util.TestVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.util.Properties;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Unit tests for {@link SyncOrchestrator}.
 *
 * <p>{@link GitService} and {@link NotificationHook} are mocked — no real Git
 * operations are performed. {@link SyncEventQueue} is a real instance — it contains
 * pure logic with no side effects.</p>
 *
 * <p>Each test follows the ARRANGE / ACT / ASSERT pattern. Ordering constraints are
 * verified via {@link InOrder} where the sequence matters.</p>
 *
 * <p><strong>Matcher rule</strong>: whenever a stubbed method mixes a literal argument
 * with a wildcard, all arguments must use explicit matchers —
 * {@code eq(testVault)} for the literal, {@code anyString()} for the wildcard.</p>
 *
 * <p><strong>SYNCHRONIZE scope</strong>: the orchestrator delegates the entire
 * SYNCHRONIZE workflow to {@link GitService#synchronize(String)}. Tests for this
 * event type verify the delegation boundary only — internal Git operations
 * (commit, pull, backup, push) are covered by {@code GitServiceTest}.</p>
 */
class SyncOrchestratorTest {

    private static TestVault testVault;
    private static LogService logService;
    private String vaultId;
    private AutoCloseable mocks;
    private String testVaultPath;
    private NotificationHook notificationHook;
    private GitService gitService;
    private SyncOrchestrator orchestrator;

    @BeforeAll
    static void prepareLogService() throws IOException {
        testVault = TestUtil.getTestVault("SyncOrchestratorTest");
        logService = new LogService(TestUtil.forLogService(testVault, LogLevel.DEBUG));
    }

    @BeforeEach
    void setUp() throws IOException {
        Properties properties = TestUtil.forOrchestrator(testVault);
        vaultId          = UUID.randomUUID().toString();
        mocks            = openMocks(this);
        notificationHook = mock(NotificationHook.class);
        gitService       = mock(GitService.class);
        testVaultPath = properties.getProperty("vault.path");
        orchestrator     = new SyncOrchestrator(
                properties, gitService, logService,
                new SyncEventQueue(logService), notificationHook);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    // ── PULL_LOGON ────────────────────────────────────────────────────────────

    @Test
    void pullLogon_dirtyTree_executesStashPullStashPop()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.hasUncommittedChanges(testVaultPath)).thenReturn(true);

        orchestrator.execute(new SyncEvent(EventType.PULL_LOGON, vaultId));

        InOrder inOrder = inOrder(gitService);
        inOrder.verify(gitService).hasUncommittedChanges(testVaultPath);
        inOrder.verify(gitService).stash(testVaultPath);
        inOrder.verify(gitService).pull(testVaultPath);
        inOrder.verify(gitService).stashPop(testVaultPath);
    }

    @Test
    void pullLogon_cleanTree_executesPullOnly()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.hasUncommittedChanges(testVaultPath)).thenReturn(false);

        orchestrator.execute(new SyncEvent(EventType.PULL_LOGON, vaultId));

        InOrder inOrder = inOrder(gitService);
        inOrder.verify(gitService).hasUncommittedChanges(testVaultPath);
        inOrder.verify(gitService).pull(testVaultPath);
        verify(gitService, never()).stash(testVaultPath);
        verify(gitService, never()).stashPop(testVaultPath);
    }

    // ── SYNCHRONIZE ───────────────────────────────────────────────────────────

    @Test
    void synchronize_always_delegatesToGitServiceSynchronize()
            throws GitException, NetworkException, InterruptedException, VaultException {
        orchestrator.execute(new SyncEvent(EventType.SYNCHRONIZE, vaultId));

        verify(gitService).synchronize(testVaultPath);
    }

    @Test
    void synchronize_never_callsStashOrStashPop()
            throws GitException, NetworkException, InterruptedException, VaultException {
        orchestrator.execute(new SyncEvent(EventType.SYNCHRONIZE, vaultId));

        verify(gitService, never()).stash(testVaultPath);
        verify(gitService, never()).stashPop(testVaultPath);
    }

    // ── PUSH_LOGOFF ───────────────────────────────────────────────────────────

    @Test
    void pushLogoff_withChanges_commitsAndPushes()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.commitLocal(eq(testVaultPath), anyString())).thenReturn(0);

        orchestrator.execute(new SyncEvent(EventType.PUSH_LOGOFF, vaultId));

        InOrder inOrder = inOrder(gitService);
        inOrder.verify(gitService).commitLocal(eq(testVaultPath), anyString());
        inOrder.verify(gitService).push(testVaultPath);
    }

    @Test
    void pushLogoff_alwaysPushes()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.commitLocal(eq(testVaultPath), anyString())).thenReturn(1);

        orchestrator.execute(new SyncEvent(EventType.PUSH_LOGOFF, vaultId));

        verify(gitService).commitLocal(eq(testVaultPath), anyString());
        verify(gitService).push(testVaultPath);
    }

    // ── AUTOSAVE ──────────────────────────────────────────────────────────────

    @Test
    void autosave_withChanges_commitsLocally()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.hasUncommittedChanges(testVaultPath)).thenReturn(true);

        orchestrator.execute(new SyncEvent(EventType.AUTOSAVE, vaultId));

        InOrder inOrder = inOrder(gitService);
        inOrder.verify(gitService).hasUncommittedChanges(testVaultPath);
        inOrder.verify(gitService).commitLocal(eq(testVaultPath), anyString());
        verify(gitService, never()).push(testVaultPath);
    }

    @Test
    void autosave_noChanges_skipsCommit()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.hasUncommittedChanges(testVaultPath)).thenReturn(false);

        orchestrator.execute(new SyncEvent(EventType.AUTOSAVE, vaultId));

        verify(gitService).hasUncommittedChanges(testVaultPath);
        verify(gitService, never()).commitLocal(eq(testVaultPath), anyString());
        verify(gitService, never()).push(testVaultPath);
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void execute_networkException_schedulesRetry()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.hasUncommittedChanges(testVaultPath)).thenReturn(false);
        doThrow(new NetworkException("timeout", null)).when(gitService).pull(testVaultPath);

        SyncEvent event = new SyncEvent(EventType.PULL_LOGON, vaultId,
                System.currentTimeMillis(), 10);
        orchestrator.execute(event);

        verify(notificationHook, never()).onFailure(any(), anyString());
    }

    @Test
    void execute_gitException_notifiesImmediately()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.hasUncommittedChanges(testVaultPath)).thenReturn(false);
        doThrow(new GitException("conflict", null)).when(gitService).pull(testVaultPath);

        orchestrator.execute(new SyncEvent(EventType.PULL_LOGON, vaultId));

        verify(notificationHook).onFailure(any(), anyString());
    }
}
