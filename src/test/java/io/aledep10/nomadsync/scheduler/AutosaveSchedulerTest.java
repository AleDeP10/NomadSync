package io.aledep10.nomadsync.scheduler;

import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.orchestrator.SyncEventQueue;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.util.TestUtil;
import io.aledep10.nomadsync.util.TestVault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for {@link AutosaveScheduler}.
 *
 * <p>Uses a package-private constructor with millisecond intervals
 * to avoid waiting real minutes during test execution.</p>
 */
class AutosaveSchedulerTest {

    static TestVault  testVault;
    static LogService logService;
    SyncEventQueue queue;

    @BeforeAll
    static void prepareLogService() throws IOException {
        testVault = TestUtil.getTestVault("AutosaveSchedulerTest");
        Properties properties = new Properties();
        properties.setProperty("log.path",  testVault.logFilePath().toString());
        properties.setProperty("log.level", "DEBUG");
        logService = new LogService(properties, testVault.rootPath());
    }

    @AfterAll
    static void closeLogService() {
        logService.close();
    }

    @BeforeEach
    void prepareQueue() {
        queue = new SyncEventQueue(logService);
    }

    @AfterEach
    void cleanup() throws IOException {
        TestUtil.cleanup(testVault);
    }

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