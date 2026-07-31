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
 * and save operations. Named {@code CatalogDto}, not {@code VaultRootDto} —
 * consistency with the rest of the Vault domain's own vocabulary
 * ({@code VaultService.catalogFile}, {@code catalog.json}), which never uses
 * "root" anywhere else.</p>
 *
 * <p>A plain class with an explicit {@link #getVaults()} getter, not a
 * record — converged with {@link VaultDto} (record accessors can't be renamed
 * to bean style without an unrelated duplicate method).</p>
 *
 * <h2>Root DTO — a recurring micro-pattern, not a shared abstraction</h2>
 * <p>{@link WorkspacesRegistryDto} follows the identical recipe for
 * {@code workspaces.json}: a single field, a {@code @JsonCreator} constructor
 * with one {@code @JsonProperty}, one plain getter. The two are deliberately
 * <strong>not</strong> unified under a shared generic base — Jackson still
 * needs a distinct {@code @JsonProperty} key per file ({@code "vaults"} vs.
 * {@code "workspaces"}), so a shared base would only save the field and getter
 * declarations at the cost of an inheritance hierarchy for two lines of code.
 * With only two concrete instances, that trade isn't worth it (same reasoning
 * already applied when a shared {@code Catalog}/{@code WorkspacesRegistry}
 * domain root was considered and dropped). If a third root DTO is ever needed,
 * follow this same recipe rather than reaching for a base class at that point
 * either — only a genuine rule-of-three case would justify revisiting this.</p>
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
public class CatalogDto {

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
    public CatalogDto(@JsonProperty("vaults") List<VaultDto> vaults) {
        this.vaults = vaults;
    }

    /**
     * Returns the list of vault DTOs.
     */
    public List<VaultDto> getVaults() {
        return vaults;
    }
}
