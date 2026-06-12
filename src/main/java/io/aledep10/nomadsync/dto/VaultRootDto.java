package io.aledep10.nomadsync.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Jackson DTO for the root wrapper of {@code vaults.json}.
 *
 * <p>Maps the top-level structure {@code { "vaults": [...] }} to a list of
 * {@link VaultDto} entries. Used exclusively by
 * {@link io.aledep10.nomadsync.util.JsonMapper} for load and save operations.</p>
 *
 * <h2>File structure</h2>
 * <pre>{@code
 * {
 *   "vaults": [
 *     { "id": "...", "owner": "AleDeP10", "name": "public-vault", "path": "..." },
 *     ...
 *   ]
 * }
 * }</pre>
 */
public class VaultRootDto {

    private final List<VaultDto> vaults;

    /**
     * Jackson deserialisation constructor.
     *
     * @param vaults the list of vault DTOs from the JSON array
     */
    @JsonCreator
    public VaultRootDto(@JsonProperty("vaults") List<VaultDto> vaults) {
        this.vaults = vaults;
    }

    /** Returns the list of vault DTOs. */
    public List<VaultDto> getVaults() { return vaults; }
}