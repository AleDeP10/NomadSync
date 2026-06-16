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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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
 * {@code eq(vault)} for the literal, {@code anyString()} for the wildcard.</p>
 *
 * <p><strong>SYNCHRONIZE scope</strong>: the orchestrator delegates the entire
 * SYNCHRONIZE workflow to {@link GitService#synchronize(Vault)}. Tests for this
 * event type verify the delegation boundary only — internal Git operations
 * (commit, pull, backup, push) are covered by {@code GitServiceTest}.</p>
 *
 * <p><strong>COMMIT_MANUAL scope</strong>: tests verify that the user-provided
 * message from {@link SyncEvent#getMessage()} is passed to {@link GitService#commitLocal},
 * and that a blank message triggers the fallback.</p>
 */
class SyncOrchestratorTest {

    private static TestVault testVault;
    private static LogService logService;

    private String vaultId;
    private Vault vault;
    private AutoCloseable mocks;
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
        vaultId          = UUID.randomUUID().toString();
        mocks            = openMocks(this);
        notificationHook = mock(NotificationHook.class);
        gitService       = mock(GitService.class);

        vault = new Vault(vaultId, "AleDeP10", "test-vault",
                testVault.vaultPath().toString());

        orchestrator = new SyncOrchestrator(
                vault, gitService, logService,
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
        when(gitService.hasUncommittedChanges(vault)).thenReturn(true);

        orchestrator.execute(new SyncEvent(EventType.PULL_LOGON, vaultId));

        InOrder inOrder = inOrder(gitService);
        inOrder.verify(gitService).hasUncommittedChanges(vault);
        inOrder.verify(gitService).stash(vault);
        inOrder.verify(gitService).pull(vault);
        inOrder.verify(gitService).stashPop(vault);
    }

    @Test
    void pullLogon_cleanTree_executesPullOnly()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.hasUncommittedChanges(vault)).thenReturn(false);

        orchestrator.execute(new SyncEvent(EventType.PULL_LOGON, vaultId));

        InOrder inOrder = inOrder(gitService);
        inOrder.verify(gitService).hasUncommittedChanges(vault);
        inOrder.verify(gitService).pull(vault);
        verify(gitService, never()).stash(vault);
        verify(gitService, never()).stashPop(vault);
    }

    // ── SYNCHRONIZE ───────────────────────────────────────────────────────────

    @Test
    void synchronize_always_delegatesToGitServiceSynchronize()
            throws GitException, NetworkException, InterruptedException, VaultException {
        orchestrator.execute(new SyncEvent(EventType.SYNCHRONIZE, vaultId));

        verify(gitService).synchronize(vault);
    }

    @Test
    void synchronize_never_callsStashOrStashPop()
            throws GitException, NetworkException, InterruptedException, VaultException {
        orchestrator.execute(new SyncEvent(EventType.SYNCHRONIZE, vaultId));

        verify(gitService, never()).stash(vault);
        verify(gitService, never()).stashPop(vault);
    }

    // ── PUSH_LOGOFF ───────────────────────────────────────────────────────────

    @Test
    void pushLogoff_withChanges_commitsAndPushes()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.commitLocal(eq(vault), anyString())).thenReturn(0);

        orchestrator.execute(new SyncEvent(EventType.PUSH_LOGOFF, vaultId));

        InOrder inOrder = inOrder(gitService);
        inOrder.verify(gitService).commitLocal(eq(vault), anyString());
        inOrder.verify(gitService).push(vault);
    }

    @Test
    void pushLogoff_alwaysPushes()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.commitLocal(eq(vault), anyString())).thenReturn(1);

        orchestrator.execute(new SyncEvent(EventType.PUSH_LOGOFF, vaultId));

        verify(gitService).commitLocal(eq(vault), anyString());
        verify(gitService).push(vault);
    }

    // ── COMMIT_MANUAL ─────────────────────────────────────────────────────────

    @Test
    void commitManual_withChangesAndMessage_commitsWithUserMessage()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.hasUncommittedChanges(vault)).thenReturn(true);

        orchestrator.execute(new SyncEvent(EventType.COMMIT_MANUAL, vaultId, "my commit message"));

        verify(gitService).commitLocal(vault, "my commit message");
        verify(gitService, never()).push(vault);
    }

    @Test
    void commitManual_withChangesAndBlankMessage_usesTimestampFallback()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.hasUncommittedChanges(vault)).thenReturn(true);

        orchestrator.execute(new SyncEvent(EventType.COMMIT_MANUAL, vaultId, "  "));

        // message was blank — fallback used, should contain "manual commit"
        verify(gitService).commitLocal(eq(vault), contains("manual commit"));
        verify(gitService, never()).push(vault);
    }

    @Test
    void commitManual_noChanges_skipsCommit()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.hasUncommittedChanges(vault)).thenReturn(false);

        orchestrator.execute(new SyncEvent(EventType.COMMIT_MANUAL, vaultId, "irrelevant"));

        verify(gitService).hasUncommittedChanges(vault);
        verify(gitService, never()).commitLocal(any(), anyString());
        verify(gitService, never()).push(vault);
    }

    // ── AUTOSAVE ──────────────────────────────────────────────────────────────

    @Test
    void autosave_withChanges_commitsLocally()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.hasUncommittedChanges(vault)).thenReturn(true);

        orchestrator.execute(new SyncEvent(EventType.AUTOSAVE, vaultId));

        InOrder inOrder = inOrder(gitService);
        inOrder.verify(gitService).hasUncommittedChanges(vault);
        inOrder.verify(gitService).commitLocal(eq(vault), anyString());
        verify(gitService, never()).push(vault);
    }

    @Test
    void autosave_noChanges_skipsCommit()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.hasUncommittedChanges(vault)).thenReturn(false);

        orchestrator.execute(new SyncEvent(EventType.AUTOSAVE, vaultId));

        verify(gitService).hasUncommittedChanges(vault);
        verify(gitService, never()).commitLocal(any(), anyString());
        verify(gitService, never()).push(vault);
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void execute_networkException_schedulesRetry()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.hasUncommittedChanges(vault)).thenReturn(false);
        doThrow(new NetworkException("timeout", null)).when(gitService).pull(vault);

        SyncEvent event = new SyncEvent(EventType.PULL_LOGON, vaultId, null,
                System.currentTimeMillis(), 10);
        orchestrator.execute(event);

        verify(notificationHook, never()).onFailure(any(), anyString());
    }

    @Test
    void execute_gitException_notifiesImmediately()
            throws GitException, NetworkException, InterruptedException {
        when(gitService.hasUncommittedChanges(vault)).thenReturn(false);
        doThrow(new GitException("conflict", null)).when(gitService).pull(vault);

        orchestrator.execute(new SyncEvent(EventType.PULL_LOGON, vaultId));

        verify(notificationHook).onFailure(any(), anyString());
    }

    @Test
    void execute_vaultException_isSwallowedAndDoesNotNotify()
            throws GitException, NetworkException, InterruptedException, VaultException {
        doThrow(new VaultException("snapshot failed")).when(gitService).synchronize(vault);

        orchestrator.execute(new SyncEvent(EventType.SYNCHRONIZE, vaultId));

        verify(notificationHook, never()).onFailure(any(), anyString());
    }
}