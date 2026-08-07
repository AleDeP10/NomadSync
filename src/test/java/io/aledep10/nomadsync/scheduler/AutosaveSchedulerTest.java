package io.aledep10.nomadsync.scheduler;

import io.aledep10.nomadsync.config.NomadPropertiesLoader;
import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.orchestrator.SyncEventQueue;
import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.util.ClassFailureTracker;
import io.aledep10.nomadsync.util.TempDirCleanupExtension;
import io.aledep10.nomadsync.util.TestUtil;
import io.aledep10.nomadsync.util.TestVault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for {@link AutosaveScheduler}.
 *
 * <p>Uses a package-private constructor with millisecond intervals
 * to avoid waiting real minutes during test execution.</p>
 *
 * <p>The shared {@link #testVault} (created once in {@code @BeforeAll}) exists
 * only to give {@link #logService} a place to write its log file. Cleaned up
 * in {@code @AfterAll} only if every test in this class passed (see
 * {@link ClassFailureTracker}) — <strong>never</strong> per-test: the previous
 * version of this file deleted the shared {@code testVault} after every single
 * test while {@code logService} still held an open file handle on its log
 * file, a real risk of a locked-file deletion failure on Windows.</p>
 */
@ExtendWith({TempDirCleanupExtension.class, ClassFailureTracker.class})
class AutosaveSchedulerTest {

    static TestVault  testVault;
    static LogService logService;
    SyncEventQueue queue;

    @BeforeAll
    static void prepareLogService() throws IOException {
        testVault  = TestUtil.getTestVault("AutosaveSchedulerTest");
        logService = new LogService(NomadPropertiesLoader.forTesting(
                TestUtil.forLogService(testVault, LogLevel.DEBUG)), testVault.rootPath());
    }

    @AfterAll
    static void tearDownAll(ExtensionContext context) throws IOException {
        logService.close();
        if (!ClassFailureTracker.anyTestFailed(context)) {
            TestUtil.cleanup(testVault);
        }
    }

    @BeforeEach
    void prepareQueue() {
        queue = new SyncEventQueue(logService);
    }

    // No @AfterEach — nothing per-test needs cleanup; the shared testVault is
    // handled once in @AfterAll above.

    @Test
    void start_afterInterval_publishesAutosaveEvent() throws InterruptedException {
        AutosaveScheduler scheduler = new AutosaveScheduler(queue, logService, 1, TimeUnit.MILLISECONDS);

        scheduler.start();
        Thread.sleep(50);   // wait longer than one interval

        assertThat(queue.size()).isGreaterThanOrEqualTo(1);
        assertThat(queue.consume().getType()).isEqualTo(EventType.AUTOSAVE);

        scheduler.stop();
    }

    @Test
    void stop_preventsSubsequentPublishing() throws InterruptedException {
        AutosaveScheduler scheduler = new AutosaveScheduler(queue, logService, 3, TimeUnit.MILLISECONDS);

        scheduler.start();
        Thread.sleep(50);   // let at least one event be published
        scheduler.stop();

        // drain queue
        while (!queue.isEmpty()) {
            queue.consume();
        }

        Thread.sleep(50);   // wait another cycle — no new events expected
        assertThat(queue.size()).isEqualTo(0);
    }
}