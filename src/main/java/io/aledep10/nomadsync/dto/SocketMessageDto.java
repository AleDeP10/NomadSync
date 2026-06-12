package io.aledep10.nomadsync.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aledep10.nomadsync.orchestrator.SyncEvent;

/**
 * Jackson DTO for deserialising incoming socket messages.
 *
 * <p>Keeps all Jackson annotations out of the {@link SyncEvent} domain class.
 * Used by {@link io.aledep10.nomadsync.util.JsonMapper#toSyncEvent(String)} to
 * parse the JSON received from {@link io.aledep10.nomadsync.tray.SocketClient}.</p>
 *
 * <h2>Wire format</h2>
 * <pre>{@code
 * {
 *   "event":   "SYNCHRONIZE",
 *   "vaultId": "A768-6CF3-10B-0002"
 * }
 * }</pre>
 */
public class SocketMessageDto {

    private final String event;
    private final String vaultId;

    /**
     * Jackson deserialisation constructor.
     *
     * @param event   the event type string — mapped to
     *                {@link io.aledep10.nomadsync.orchestrator.EventType} via
     *                {@code EventType.valueOf(event)}
     * @param vaultId the target vault UUID, or {@code null} for broadcast events
     *                such as {@code AUTOSAVE}
     */
    @JsonCreator
    public SocketMessageDto(
            @JsonProperty("event")   String event,
            @JsonProperty("vaultId") String vaultId) {
        this.event   = event;
        this.vaultId = vaultId;
    }

    /** Returns the raw event type string. */
    public String getEvent()   { return event; }

    /** Returns the target vault UUID, or {@code null} for broadcast events. */
    public String getVaultId() { return vaultId; }
}