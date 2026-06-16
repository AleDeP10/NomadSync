package io.aledep10.nomadsync.orchestrator;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/**
 * Unit tests for {@link SyncEvent}.
 *
 * <p>Verifies natural ordering via {@link Comparable}, retry state progression,
 * broadcast expansion via {@link SyncEvent#forVault(String)}, and the optional
 * commit message field used by {@link EventType#COMMIT_MANUAL}.</p>
 */
class SyncEventTest {

    static final String VAULT_ID = UUID.randomUUID().toString();

    // ── compareTo() ───────────────────────────────────────────────────────────

    @Test
    void compareTo_higherPriority_precedesLower() {
        SyncEvent eventHigh = new SyncEvent(EventType.PULL_LOGON, VAULT_ID);
        SyncEvent eventLow  = new SyncEvent(EventType.AUTOSAVE,   VAULT_ID);

        assertThat(eventHigh.compareTo(eventLow)).isLessThan(0);
    }

    @Test
    void compareTo_lowerPriority_followsHigher() {
        SyncEvent eventHigh = new SyncEvent(EventType.PULL_LOGON, VAULT_ID);
        SyncEvent eventLow  = new SyncEvent(EventType.AUTOSAVE,   VAULT_ID);

        assertThat(eventLow.compareTo(eventHigh)).isGreaterThan(0);
    }

    @Test
    void compareTo_samePriority_olderPrecedesNewer() {
        SyncEvent eventOld = new SyncEvent(EventType.AUTOSAVE, VAULT_ID, null, 1000L, 50L);
        SyncEvent eventNew = new SyncEvent(EventType.AUTOSAVE, VAULT_ID, null, 2000L, 50L);

        assertThat(eventOld.compareTo(eventNew)).isLessThan(0);
    }

    @Test
    void compareTo_samePriority_sameTimestamp_returnsZero() {
        SyncEvent event1 = new SyncEvent(EventType.AUTOSAVE, VAULT_ID, null, 1000L, 50L);
        SyncEvent event2 = new SyncEvent(EventType.AUTOSAVE, VAULT_ID, null, 1000L, 50L);

        assertThat(event1.compareTo(event2)).isEqualTo(0);
    }

    /**
     * Verifies that all five priority levels are ordered correctly relative
     * to each other — not just the extremes.
     */
    @Test
    void compareTo_allEventTypes_orderedByPriority() {
        SyncEvent pull        = new SyncEvent(EventType.PULL_LOGON,    VAULT_ID, null, 0L, 0L);
        SyncEvent synchronize = new SyncEvent(EventType.SYNCHRONIZE,   VAULT_ID, null, 0L, 0L);
        SyncEvent push        = new SyncEvent(EventType.PUSH_LOGOFF,   VAULT_ID, null, 0L, 0L);
        SyncEvent commit      = new SyncEvent(EventType.COMMIT_MANUAL, VAULT_ID, null, 0L, 0L);
        SyncEvent autosave    = new SyncEvent(EventType.AUTOSAVE,      VAULT_ID, null, 0L, 0L);

        assertThat(pull.compareTo(synchronize)).isLessThan(0);
        assertThat(synchronize.compareTo(push)).isLessThan(0);
        assertThat(push.compareTo(commit)).isLessThan(0);
        assertThat(commit.compareTo(autosave)).isLessThan(0);
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

    // ── forVault() ────────────────────────────────────────────────────────────

    /**
     * Verifies that {@code forVault()} expands a broadcast event into a
     * vault-targeted copy while preserving all other fields.
     */
    @Test
    void forVault_broadcastEvent_returnsTargetedCopy() {
        SyncEvent broadcast = new SyncEvent(EventType.AUTOSAVE, null, null, 5000L, 100L);

        SyncEvent targeted = broadcast.forVault(VAULT_ID);

        assertThat(targeted.getVaultId()).isEqualTo(VAULT_ID);
        assertThat(targeted.getType()).isEqualTo(EventType.AUTOSAVE);
        assertThat(targeted.getTimestamp()).isEqualTo(5000L);
        assertThat(targeted.getRetryDelay()).isEqualTo(100L);
        assertThat(targeted.getRetryCount()).isEqualTo(0);
    }

    /**
     * Verifies that the expanded event is a distinct object — mutations to one
     * do not affect the other.
     */
    @Test
    void forVault_returnedEvent_isDistinctInstance() {
        SyncEvent broadcast = new SyncEvent(EventType.AUTOSAVE, null);

        SyncEvent targeted = broadcast.forVault(VAULT_ID);

        targeted.incrementRetry();
        assertThat(broadcast.getRetryCount()).isEqualTo(0);
        assertThat(targeted.getRetryCount()).isEqualTo(1);
    }

    /**
     * Verifies that calling {@code forVault()} on an event that already has a
     * {@code vaultId} throws {@link UnsupportedOperationException}.
     */
    @Test
    void forVault_alreadyTargeted_throwsUnsupportedOperationException() {
        SyncEvent targeted = new SyncEvent(EventType.AUTOSAVE, VAULT_ID);

        assertThatThrownBy(() -> targeted.forVault(UUID.randomUUID().toString()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(VAULT_ID);
    }

    // ── message field ─────────────────────────────────────────────────────────

    /**
     * Verifies that a {@link EventType#COMMIT_MANUAL} event carries its message.
     */
    @Test
    void message_commitManualEvent_returnsUserMessage() {
        SyncEvent event = new SyncEvent(EventType.COMMIT_MANUAL, VAULT_ID, "my commit");

        assertThat(event.getMessage()).isEqualTo("my commit");
    }

    /**
     * Verifies that non-COMMIT_MANUAL events have a {@code null} message
     * when constructed with the two-argument constructor.
     */
    @Test
    void message_nonCommitEvent_returnsNull() {
        SyncEvent event = new SyncEvent(EventType.AUTOSAVE, VAULT_ID);

        assertThat(event.getMessage()).isNull();
    }

    /**
     * Verifies that {@code forVault()} preserves the commit message.
     */
    @Test
    void forVault_preservesMessage() {
        SyncEvent broadcast = new SyncEvent(EventType.COMMIT_MANUAL, null, "preserved message",
                1000L, 50L);

        SyncEvent targeted = broadcast.forVault(VAULT_ID);

        assertThat(targeted.getMessage()).isEqualTo("preserved message");
    }

    // ── toString() ────────────────────────────────────────────────────────────

    @Test
    void toString_containsExpectedFields() {
        SyncEvent event = new SyncEvent(EventType.PULL_LOGON, VAULT_ID, null, 0L, 50L);

        assertThat(event.toString()).contains(EventType.PULL_LOGON.toString());
        assertThat(event.toString()).contains("retryCount=0");
        assertThat(event.toString()).contains("retryDelay=50ms");
    }
}