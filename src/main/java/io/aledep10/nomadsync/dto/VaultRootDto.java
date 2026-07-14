package io.aledep10.nomadsync.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aledep10.nomadsync.util.JsonMapper;

import java.util.List;

/**
 * Jackson DTO for the root wrapper of {@code catalog.json}.
 *
 * <p>Maps the top-level structure {@code { "vaults": [...] }} to a list of
 * {@link VaultDto} entries. Used exclusively by
 * {@link JsonMapper} for load and save operations.</p>
 *
 * <h2>File structure</h2>
 * <pre>{@code
 * {
 *   "vaults": [
 *     { "id": "...", "owner": "Owner", "name": "portfolio", "path": "..." },
 *     ...
 *   ]
 * }
 * }</pre>
 */
public record VaultRootDto(List<VaultDto> vaults) {

    /**
     * Jackson deserialisation constructor.
     *
     * @param vaults the list of vault DTOs from the JSON array
     */
    @JsonCreator
    public VaultRootDto(@JsonProperty("vaults") List<VaultDto> vaults) {
        this.vaults = vaults;
    }

    /**
     * Returns the list of vault DTOs.
     */
    @Override
    public List<VaultDto> vaults() {
        return vaults;
    }
}