package io.aledep10.nomadSync.orchestrator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for {@link SyncEvent}.
 *
 * <p>Verifies natural ordering via {@link Comparable},
 * retry state progression, and string representation.</p>
 */
class SyncEventTest {

    // ── compareTo() ───────────────────────────────────────────────────────────

    @Test
    void compareTo_higherPriority_precedesLower() {
        SyncEvent eventHigh = new SyncEvent(EventType.PULL_LOGON);
        SyncEvent eventLow  = new SyncEvent(EventType.AUTOSAVE);

        assertThat(eventHigh.compareTo(eventLow)).isLessThan(0);
    }

    @Test
    void compareTo_samePriority_olderPrecedesNewer() {
        SyncEvent eventOld = new SyncEvent(EventType.AUTOSAVE, 1000L);
        SyncEvent eventNew = new SyncEvent(EventType.AUTOSAVE, 2000L);

        assertThat(eventOld.compareTo(eventNew)).isLessThan(0);
    }

    @Test
    void compareTo_samePriority_sameTimestamp_returnsZero() {
        SyncEvent event1 = new SyncEvent(EventType.AUTOSAVE, 1000L);
        SyncEvent event2 = new SyncEvent(EventType.AUTOSAVE, 1000L);

        assertThat(event1.compareTo(event2)).isEqualTo(0);
    }

    // ── incrementRetry() ──────────────────────────────────────────────────────

    @Test
    void incrementRetry_updatesCounterAndDoublesDelay() {
        SyncEvent event = new SyncEvent(EventType.AUTOSAVE);

        assertThat(event.getRetryCount()).isEqualTo(0);
        assertThat(event.getRetryDelay()).isEqualTo(SyncEvent.INITIAL_RETRY_DELAY_MS);

        event.incrementRetry();
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getRetryDelay()).isEqualTo(SyncEvent.INITIAL_RETRY_DELAY_MS * 2);

        event.incrementRetry();
        assertThat(event.getRetryCount()).isEqualTo(2);
        assertThat(event.getRetryDelay()).isEqualTo(SyncEvent.INITIAL_RETRY_DELAY_MS * 4);
    }

    // ── toString() ────────────────────────────────────────────────────────────

    @Test
    void toString_containsExpectedFields() {
        SyncEvent event = new SyncEvent(EventType.PULL_LOGON, 0L);

        assertThat(event.toString()).contains(EventType.PULL_LOGON.toString());
        assertThat(event.toString()).contains("retryCount=0");
        assertThat(event.toString()).contains("retryDelay=" + SyncEvent.INITIAL_RETRY_DELAY_MS + "ms");
    }
}