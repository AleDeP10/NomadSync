package io.aledep10.nomadSync.orchestrator;

import io.aledep10.nomadSync.hook.NotificationHook;
import io.aledep10.nomadSync.service.GitService;
import io.aledep10.nomadSync.service.LogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;

import java.io.IOException;
import java.util.Properties;

import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Unit tests for {@link SyncOrchestrator}.
 *
 * <p>GitService and NotificationHook are mocked — no real Git operations are performed.
 * SyncEventQueue is a real instance — it contains pure logic with no side effects.</p>
 *
 * <p>Each test follows the ARRANGE / ACT / ASSERT pattern and verifies
 * both the presence and the order of Git operations via {@link InOrder}.</p>
 */
class SyncOrchestratorTest {

    private static LogService logService;

    AutoCloseable mocks;

    @Mock
    private NotificationHook notificationHook;

    @Mock
    private GitService gitService;

    private SyncOrchestrator orchestrator;

    @BeforeAll
    static void prepareLogService() {
        Properties properties = new Properties();
        properties.setProperty("log.path",  System.getProperty("java.io.tmpdir") + "/nomadSync-test.log");
        properties.setProperty("log.level", "DEBUG");
        logService = new LogService(properties);
    }

    @BeforeEach
    void setUp() {
        mocks        = openMocks(this);
        notificationHook = mock(NotificationHook.class);
        gitService       = mock(GitService.class);
        orchestrator     = new SyncOrchestrator(gitService, logService,
                new SyncEventQueue(logService), notificationHook);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    // ── PULL_LOGON ────────────────────────────────────────────────────────────

    @Test
    void pullLogon_dirtyTree_executesStashPullStashPop() throws InterruptedException, IOException {
        when(gitService.hasUncommittedChanges()).thenReturn(true);

        orchestrator.execute(new SyncEvent(EventType.PULL_LOGON));

        InOrder inOrder = inOrder(gitService);
        inOrder.verify(gitService).hasUncommittedChanges();
        inOrder.verify(gitService).stash();
        inOrder.verify(gitService).pull();
        inOrder.verify(gitService).stashPop();
    }

    @Test
    void pullLogon_cleanTree_executesPullOnly() throws InterruptedException, IOException {
        when(gitService.hasUncommittedChanges()).thenReturn(false);

        orchestrator.execute(new SyncEvent(EventType.PULL_LOGON));

        InOrder inOrder = inOrder(gitService);
        inOrder.verify(gitService).hasUncommittedChanges();
        inOrder.verify(gitService).pull();
        verify(gitService, never()).stash();
        verify(gitService, never()).stashPop();
    }

    // ── PUSH_LOGOFF ───────────────────────────────────────────────────────────

    @Test
    void pushLogoff_withChanges_commitsAndPushes() throws InterruptedException, IOException {
        when(gitService.commitLocal(anyString())).thenReturn(0);

        orchestrator.execute(new SyncEvent(EventType.PUSH_LOGOFF));

        InOrder inOrder = inOrder(gitService);
        inOrder.verify(gitService).commitLocal(anyString());
        inOrder.verify(gitService).push();
    }

    @Test
    void pushLogoff_noChanges_skipsRemotePush() throws InterruptedException, IOException {
        when(gitService.commitLocal(anyString())).thenReturn(1);

        orchestrator.execute(new SyncEvent(EventType.PUSH_LOGOFF));

        verify(gitService).commitLocal(anyString());
        verify(gitService, never()).push();
    }

    // ── AUTOSAVE ──────────────────────────────────────────────────────────────

    @Test
    void autosave_withChanges_commitsLocally() throws InterruptedException, IOException {
        when(gitService.hasChanges()).thenReturn(true);

        orchestrator.execute(new SyncEvent(EventType.AUTOSAVE));

        InOrder inOrder = inOrder(gitService);
        inOrder.verify(gitService).hasChanges();
        inOrder.verify(gitService).commitLocal(anyString());
        verify(gitService, never()).push();
    }

    @Test
    void autosave_noChanges_skipsCommit() throws IOException, InterruptedException {
        when(gitService.hasChanges()).thenReturn(false);

        orchestrator.execute(new SyncEvent(EventType.AUTOSAVE));

        verify(gitService).hasChanges();
        verify(gitService, never()).commitLocal(anyString());
        verify(gitService, never()).push();
    }
}