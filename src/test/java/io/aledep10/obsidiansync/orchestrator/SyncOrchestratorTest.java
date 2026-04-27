package io.aledep10.obsidiansync.orchestrator;

import io.aledep10.obsidiansync.hook.NotificationHook;
import io.aledep10.obsidiansync.service.GitService;
import io.aledep10.obsidiansync.service.LogService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class SyncOrchestratorTest {

    private static LogService logService;

    @Mock
    private NotificationHook notificationHook;

    @Mock
    private GitService gitService;        // Mockito crea un sostituto

    private SyncOrchestrator orchestrator;

    @BeforeAll
    static void prepareLogService() {
        Properties properties = new Properties();
        properties.setProperty("log.path",  System.getProperty("java.io.tmpdir") + "/obsidiansync-test.log");
        properties.setProperty("log.level", "DEBUG");
        logService = new LogService(properties);
    }

    @BeforeEach
    void seUp() {// inizializza i mock
        try (AutoCloseable autoCloseable = openMocks(this)) {
            SyncEventQueue queue = new SyncEventQueue(logService);
            logService.debug("queue");
            notificationHook = mock(NotificationHook.class);
            logService.debug("hook");
            gitService = mock(GitService.class);
            logService.debug("git");
            orchestrator = new SyncOrchestrator(gitService, logService, queue, notificationHook);
            logService.info("Starting orchestrator");
        } catch(Exception e) {
            logService.error("Unable to initialize the orchestrator: " + e.getMessage(), e);
        }
    }

    @Test
    void pullLogon_success_executesStashPullStashPop() throws InterruptedException, IOException {

        // ARRANGE — definisci il comportamento del mock
        // di default i mock non fanno nulla — qui è sufficiente
        // non lanciare eccezioni
        when(gitService.hasUncommittedChanges()).thenReturn(true);

        // ACT
        orchestrator.execute(new SyncEvent(EventType.PULL_LOGON));
        // attendi che il worker loop consumi l'evento...

        // ASSERT — verifica che GitService sia stato chiamato nell'ordine corretto
        InOrder inOrder = inOrder(gitService);
        logService.debug("inOrder");
        inOrder.verify(gitService).stash();
        logService.debug("stash");
        inOrder.verify(gitService).pull();
        logService.debug("pull");
        inOrder.verify(gitService).stashPop();
        logService.debug("stashPop");
    }
}