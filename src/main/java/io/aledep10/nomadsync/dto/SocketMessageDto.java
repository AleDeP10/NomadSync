package io.aledep10.nomadsync.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class SocketMessageDto {
    private final String event;
    private final String vaultId;

    @JsonCreator
    public SocketMessageDto(
            @JsonProperty("event")   String event,
            @JsonProperty("vaultId") String vaultId) {
        this.event   = event;
        this.vaultId = vaultId;
    }

    public String getEvent() {
        return event;
    }

    public String getVaultId() {
        return vaultId;
    }
}
