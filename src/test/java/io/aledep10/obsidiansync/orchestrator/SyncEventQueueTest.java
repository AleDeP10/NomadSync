package io.aledep10.obsidiansync.orchestrator;

import io.aledep10.obsidiansync.service.LogService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for {@link SyncEventQueue}.
 *
 * <p>Verifies publish deduplication (latest-wins), coexistence of different
 * event types, and priority ordering on consume.</p>
 */
class SyncEventQueueTest {

    static LogService logService;
    SyncEventQueue eventQueue;

    @BeforeAll
    static void prepareLogService() {
        Properties properties = new Properties();
        properties.setProperty("log.path",  System.getProperty("java.io.tmpdir") + "/obsidiansync-test.log");
        properties.setProperty("log.level", "DEBUG");
        logService = new LogService(properties);
    }

    @BeforeEach
    void prepareEventQueue() {
        eventQueue = new SyncEventQueue(logService);
    }

    // ── publish() ─────────────────────────────────────────────────────────────

    @Test
    void publish_emptyQueue_addsEvent() {
        eventQueue.publish(new SyncEvent(EventType.AUTOSAVE));

        assertThat(eventQueue.size()).isEqualTo(1);
    }

    @Test
    void publish_newerEvent_replacesOlder() throws InterruptedException {
        SyncEvent eventOld = new SyncEvent(EventType.AUTOSAVE);
        Thread.sleep(5);    // ensures distinct timestamps
        SyncEvent eventNew = new SyncEvent(EventType.AUTOSAVE);

        eventQueue.publish(eventOld);
        eventQueue.publish(eventNew);

        assertThat(eventQueue.size()).isEqualTo(1);
        assertThat(eventQueue.consume()).isEqualTo(eventNew);
    }

    @Test
    void publish_olderEvent_isDiscarded() throws InterruptedException {
        // NOTE: setTimestamp() exists solely for this test scenario.
        // The optimal solution is a package-private test constructor
        // that keeps timestamp final. Tracked in DTR.
        SyncEvent eventNew = new SyncEvent(EventType.AUTOSAVE, 2000);
        SyncEvent eventOld = new SyncEvent(EventType.AUTOSAVE, 1000);

        eventQueue.publish(eventNew);
        eventQueue.publish(eventOld);

        assertThat(eventQueue.size()).isEqualTo(1);
        assertThat(eventQueue.consume()).isEqualTo(eventNew);
    }

    @Test
    void publish_differentTypes_coexist() {
        eventQueue.publish(new SyncEvent(EventType.PULL_LOGON));
        eventQueue.publish(new SyncEvent(EventType.AUTOSAVE));

        assertThat(eventQueue.size()).isEqualTo(2);
    }

    // ── consume() ─────────────────────────────────────────────────────────────

    @Test
    void consume_returnsEventsInPriorityOrder() throws InterruptedException {
        eventQueue.publish(new SyncEvent(EventType.AUTOSAVE));
        eventQueue.publish(new SyncEvent(EventType.PULL_LOGON));
        eventQueue.publish(new SyncEvent(EventType.PUSH_LOGOFF));

        assertThat(eventQueue.consume().getType()).isEqualTo(EventType.PULL_LOGON);
        assertThat(eventQueue.consume().getType()).isEqualTo(EventType.PUSH_LOGOFF);
        assertThat(eventQueue.consume().getType()).isEqualTo(EventType.AUTOSAVE);
    }
}