package io.aledep10.nomadsync.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aledep10.nomadsync.util.JsonMapper;

import java.util.List;

/**
 * Jackson DTO for the root wrapper of {@code catalog.json}.
 *
 * <p>Maps the top-level structure {@code { "vaults": [...] }} to a list of
 * {@link VaultDto} entries. Used exclusively by {@link JsonMapper} for load
 * and save operations.</p>
 *
 * <p>A plain class with an explicit {@link #getVaults()} getter, not a
 * record — converged with {@link VaultDto} and with the workspace DTO pair
 * ({@link WorkspaceEntryDto}/{@link WorkspacesRegistryDto}), all four now
 * bean-style. A record's component accessor ({@code vaults()}) cannot be
 * renamed to bean style without an unrelated duplicate method — see the DTO
 * signature convergence discussion for the full rationale.</p>
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
public class VaultRootDto {

    private final List<VaultDto> vaults;

    /**
     * Jackson deserialisation constructor — also called directly by
     * {@link JsonMapper#saveVaultsToFile(java.io.File, List)}
     * when building the DTO for serialisation.
     *
     * @param vaults the list of vault DTOs, from the JSON array on read or
     *               from the in-memory domain list on write
     */
    @JsonCreator
    public VaultRootDto(@JsonProperty("vaults") List<VaultDto> vaults) {
        this.vaults = vaults;
    }

    /**
     * Returns the list of vault DTOs.
     */
    public List<VaultDto> getVaults() {
        return vaults;
    }
}
