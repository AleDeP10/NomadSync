package io.aledep10.nomadsync.tray;

import io.aledep10.nomadsync.tray.SocketMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Unit tests for {@link SocketMessage}.
 *
 * <p>Verifies initial state, retry progression and JSON serialisation.</p>
 */
class SocketMessageTest {

    // ── initial state ─────────────────────────────────────────────────────────

    @Test
    void constructor_setsInitialState() {
        SocketMessage message = new SocketMessage("PULL_LOGON", "test-vault", 30_000L);

        assertThat(message.getEvent()).isEqualTo("PULL_LOGON");
        assertThat(message.getVaultId()).isEqualTo("test-vault");
        assertThat(message.getRetryCount()).isEqualTo(0);
        assertThat(message.getRetryDelay()).isEqualTo(30_000L);
        assertThat(message.getTimestamp()).isGreaterThan(0L);
    }

    // ── incrementRetry() ──────────────────────────────────────────────────────

    @Test
    void incrementRetry_updatesCounterAndDoublesDelay() {
        SocketMessage message = new SocketMessage("AUTOSAVE", "test-vault", 30_000L);

        assertThat(message.getRetryCount()).isEqualTo(0);
        assertThat(message.getRetryDelay()).isEqualTo(30_000L);

        message.incrementRetry();
        assertThat(message.getRetryCount()).isEqualTo(1);
        assertThat(message.getRetryDelay()).isEqualTo(60_000L);

        message.incrementRetry();
        assertThat(message.getRetryCount()).isEqualTo(2);
        assertThat(message.getRetryDelay()).isEqualTo(120_000L);
    }

    // ── toString() ────────────────────────────────────────────────────────────

    @Test
    void toString_producesValidJson() {
        SocketMessage message = new SocketMessage("PUSH_LOGOFF", "test-vault", 30_000L);

        String json = message.toString();

        assertThat(json).contains("\"event\":\"PUSH_LOGOFF\"");
        assertThat(json).contains("\"vaultId\":\"test-vault\"");
        assertThat(json).contains("\"retryCount\":0");
        assertThat(json).contains("\"retryDelay\":30000");
        assertThat(json).contains("\"timestamp\":");
    }
}
