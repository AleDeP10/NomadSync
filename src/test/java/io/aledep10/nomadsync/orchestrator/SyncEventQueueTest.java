package io.aledep10.nomadsync.orchestrator;

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
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for {@link SyncEventQueue}.
 *
 * <p>Verifies the three core behaviours of the queue:</p>
 * <ul>
 *   <li><strong>Deduplication</strong> — latest-wins: a newer event of the same type
 *       replaces an older one; an older event is silently discarded.</li>
 *   <li><strong>Coexistence</strong> — events of different types are independent and
 *       never deduplicate each other.</li>
 *   <li><strong>Priority ordering</strong> — {@link SyncEvent#compareTo} guarantees
 *       that {@link EventType#PULL_LOGON} (priority 1) is always consumed before
 *       {@link EventType#AUTOSAVE} (priority 4), regardless of insertion order.</li>
 * </ul>
 *
 * <p>{@link LogService} is shared across all tests ({@code @BeforeAll}) since it
 * carries no mutable state relevant to queue operations. No test inspects the
 * log file's content, so a single {@code @AfterAll} cleanup is sufficient —
 * unlike suites that assert on log content per test, there is no need to reset
 * anything between individual {@code @Test} methods here. That cleanup is now
 * conditional on every test in the class having passed (see
 * {@link ClassFailureTracker}) — a failing test leaves {@code testVault}
 * (and its log file) on disk for inspection.</p>
 */
@ExtendWith({TempDirCleanupExtension.class, ClassFailureTracker.class})
class SyncEventQueueTest {

    static TestVault  testVault;
    static LogService logService;
    String vaultId;
    SyncEventQueue eventQueue;

    @BeforeAll
    static void prepareLogService() throws IOException {
        testVault = TestUtil.getTestVault("SyncEventQueueTest");
        logService = new LogService(
                TestUtil.forLogService(testVault, LogLevel.DEBUG), testVault.rootPath());
    }

    @AfterAll
    static void tearDownAll(ExtensionContext context) throws IOException {
        logService.close();
        if (!ClassFailureTracker.anyTestFailed(context)) {
            TestUtil.cleanup(testVault);
        }
    }

    @BeforeEach
    void prepareEventQueue() {
        vaultId = UUID.randomUUID().toString();
        eventQueue = new SyncEventQueue(logService);
    }

    // ── publish() — deduplication ─────────────────────────────────────────────

    /**
     * Publishing to an empty queue adds the event — size increases to 1.
     */
    @Test
    void publish_emptyQueue_addsEvent() {
        eventQueue.publish(new SyncEvent(EventType.AUTOSAVE, vaultId));

        assertThat(eventQueue.size()).isEqualTo(1);
    }

    /**
     * Publishing a newer event of the same type replaces the older one.
     * Queue size stays at 1 and consume() returns the newer event.
     */
    @Test
    void publish_newerEvent_replacesOlder() throws InterruptedException {
        SyncEvent eventOld = new SyncEvent(EventType.AUTOSAVE, vaultId);
        Thread.sleep(5);    // ensures distinct timestamps
        SyncEvent eventNew = new SyncEvent(EventType.AUTOSAVE, vaultId);

        eventQueue.publish(eventOld);
        eventQueue.publish(eventNew);

        assertThat(eventQueue.size()).isEqualTo(1);
        assertThat(eventQueue.consume()).isEqualTo(eventNew);
    }

    /**
     * Publishing an older event of the same type is silently discarded.
     * The newer event already in the queue is retained.
     *
     * <p>Uses the package-private timestamp constructor to create events with
     * controlled timestamps, avoiding {@code Thread.sleep} and non-determinism.</p>
     */
    @Test
    void publish_olderEvent_isDiscarded() throws InterruptedException {
        SyncEvent eventNew = new SyncEvent(EventType.AUTOSAVE, vaultId, null, 2000, 50);
        SyncEvent eventOld = new SyncEvent(EventType.AUTOSAVE, vaultId, null, 1000, 50);

        eventQueue.publish(eventNew);
        eventQueue.publish(eventOld);

        assertThat(eventQueue.size()).isEqualTo(1);
        assertThat(eventQueue.consume()).isEqualTo(eventNew);
    }

    /**
     * Events of different types coexist in the queue without deduplication.
     */
    @Test
    void publish_differentTypes_coexist() {
        eventQueue.publish(new SyncEvent(EventType.PULL_LOGON, vaultId));
        eventQueue.publish(new SyncEvent(EventType.AUTOSAVE, vaultId));

        assertThat(eventQueue.size()).isEqualTo(2);
    }

    // ── consume() — priority ordering ─────────────────────────────────────────

    /**
     * Events are consumed in ascending priority order regardless of insertion order.
     * PULL_LOGON (1) before PUSH_LOGOFF (3) before AUTOSAVE (4).
     */
    @Test
    void consume_returnsEventsInPriorityOrder() throws InterruptedException {
        eventQueue.publish(new SyncEvent(EventType.AUTOSAVE, vaultId));
        eventQueue.publish(new SyncEvent(EventType.PULL_LOGON, vaultId));
        eventQueue.publish(new SyncEvent(EventType.PUSH_LOGOFF, vaultId));

        assertThat(eventQueue.consume().getType()).isEqualTo(EventType.PULL_LOGON);
        assertThat(eventQueue.consume().getType()).isEqualTo(EventType.PUSH_LOGOFF);
        assertThat(eventQueue.consume().getType()).isEqualTo(EventType.AUTOSAVE);
    }
}