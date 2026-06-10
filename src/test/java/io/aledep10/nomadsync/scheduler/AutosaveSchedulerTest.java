package io.aledep10.nomadsync.scheduler;

import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.orchestrator.SyncEventQueue;
import io.aledep10.nomadsync.scheduler.AutosaveScheduler;
import io.aledep10.nomadsync.service.LogService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    static LogService logService;
    SyncEventQueue queue;

    @BeforeAll
    static void prepareLogService() {
        Properties properties = new Properties();
        properties.setProperty("log.path",  System.getProperty("java.io.tmpdir") + "/obsidiansync-test.log");
        properties.setProperty("log.level", "DEBUG");
        logService = new LogService(properties);
    }

    @BeforeEach
    void prepareQueue() {
        queue = new SyncEventQueue(logService);
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
        while (queue.size() > 0) {
            queue.consume();
        }

        Thread.sleep(50);   // wait another cycle — no new events expected
        assertThat(queue.size()).isEqualTo(0);
    }
}
