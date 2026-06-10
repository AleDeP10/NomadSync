package io.aledep10.nomadsync.orchestrator;

import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.orchestrator.SyncEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for {@link SyncEvent}.
 *
 * <p>Verifies natural ordering via {@link Comparable},
 * retry state progression, and string representation.</p>
 */
class SyncEventTest {

    static final String VAULT_ID = UUID.randomUUID().toString();

    // ── compareTo() ───────────────────────────────────────────────────────────

    @Test
    void compareTo_higherPriority_precedesLower() {
        SyncEvent eventHigh = new SyncEvent(EventType.PULL_LOGON, VAULT_ID);
        SyncEvent eventLow  = new SyncEvent(EventType.AUTOSAVE, VAULT_ID);

        assertThat(eventHigh.compareTo(eventLow)).isLessThan(0);
    }

    @Test
    void compareTo_samePriority_olderPrecedesNewer() {
        SyncEvent eventOld = new SyncEvent(EventType.AUTOSAVE, VAULT_ID, 1000L, 50L);
        SyncEvent eventNew = new SyncEvent(EventType.AUTOSAVE, VAULT_ID, 2000L, 50L);

        assertThat(eventOld.compareTo(eventNew)).isLessThan(0);
    }

    @Test
    void compareTo_samePriority_sameTimestamp_returnsZero() {
        SyncEvent event1 = new SyncEvent(EventType.AUTOSAVE, VAULT_ID, 1000L, 50L);
        SyncEvent event2 = new SyncEvent(EventType.AUTOSAVE, VAULT_ID, 1000L, 50L);

        assertThat(event1.compareTo(event2)).isEqualTo(0);
    }

    // ── incrementRetry() ──────────────────────────────────────────────────────

    @Test
    void incrementRetry_updatesCounterAndDoublesDelay() {
        SyncEvent event = new SyncEvent(EventType.AUTOSAVE, VAULT_ID);

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
        SyncEvent event = new SyncEvent(EventType.PULL_LOGON, VAULT_ID, 0L, 50L);

        assertThat(event.toString()).contains(EventType.PULL_LOGON.toString());
        assertThat(event.toString()).contains("retryCount=0");
        assertThat(event.toString()).contains("retryDelay=50ms");
    }
}
