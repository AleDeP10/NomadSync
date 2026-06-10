package io.aledep10.nomadsync.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DTO di trasporto per la persistenza su file.
 * Mappa la struttura radice { "vaults": [...] }
 */
public class VaultRootDto {
    private final List<VaultDto> vaults;

    @JsonCreator
    public VaultRootDto(@JsonProperty("vaults") List<VaultDto> vaults) {
        this.vaults = vaults;
    }

    public List<VaultDto> getVaults() {
        return vaults;
    }
}
