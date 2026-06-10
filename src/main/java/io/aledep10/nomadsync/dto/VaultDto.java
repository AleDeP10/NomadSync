package io.aledep10.nomadsync.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aledep10.nomadsync.orchestrator.Vault;

public class VaultDto {
    private final String id;
    private final String name;
    private final String path;

    @JsonCreator
    public VaultDto(
            @JsonProperty("id")   String id,
            @JsonProperty("name") String name,
            @JsonProperty("path") String path) {
        this.id = id;
        this.name = name;
        this.path = path;
    }

    public VaultDto(Vault vault) {
        this.id = vault.getId();
        this.name = vault.getName();
        this.path = vault.getPath();
    }

    public Vault asVault() {
        return new Vault(this.id, this.name, this.path);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }
}
