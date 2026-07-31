package io.aledep10.nomadsync.util;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aledep10.nomadsync.dto.*;
import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.orchestrator.SyncEvent;
import io.aledep10.nomadsync.vault.Vault;
import io.aledep10.nomadsync.marker.VaultMarker;
import io.aledep10.nomadsync.workspace.WorkspaceEntry;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * JSON serialisation and deserialisation utility for NomadSync domain objects.
 *
 * <p>Acts as the mapping layer between JSON representations (files, socket messages)
 * and the domain model ({@link SyncEvent}, {@link Vault}). All Jackson awareness
 * is concentrated here and in the {@code dto} package — domain classes carry no
 * Jackson annotations.</p>
 *
 * <p>A single {@link ObjectMapper} instance is shared across all calls — it is
 * thread-safe after initial configuration and expensive to instantiate per call.</p>
 *
 * <p>This class is not meant to be instantiated — all members are {@code static}.</p>
 */
public final class JsonMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonMapper() {}

    public static Path loadDefaultWorkspacePath(File registryFile) throws IOException {
        return loadWorkspacesFromFile(registryFile).stream()
                .filter(WorkspaceEntry::isDefault)
                .map(WorkspaceEntry::getPath)
                .filter(path -> !StringUtil.isBlank(path))
                .findFirst()
                .map(Path::of)
                .orElse(null);
    }

    public static List<WorkspaceEntry> loadWorkspacesFromFile(File file) throws IOException {
        if (!file.exists()) return List.of();
        WorkspacesRegistryDto registry = MAPPER.readValue(file, WorkspacesRegistryDto.class);
        return registry.getWorkspaces().stream().map(WorkspaceEntryDto::toDomain).toList();
    }

    public static void saveWorkspacesToFile(File workspacesFile, List<WorkspaceEntry> workspaces) throws IOException {
        WorkspacesRegistryDto registry = new WorkspacesRegistryDto(
                workspaces.stream().map(WorkspaceEntryDto::fromDomain).toList());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(workspacesFile, registry);
    }

    // ── Vault persistence ─────────────────────────────────────────────────────

    /**
     * Loads the list of vaults from {@code catalog.json}.
     *
     * <p>Deserialises via {@link CatalogDto} → {@link VaultDto} → {@link Vault}
     * to keep Jackson annotations out of the domain class. Returns an empty list
     * if the file does not exist — no exception is thrown.</p>
     *
     * @param catalogFile the {@code catalog.json} file
     * @return list of domain {@link Vault} objects, or empty list if file absent
     * @throws IOException if the file exists but cannot be read or parsed
     */
    public static List<Vault> loadVaultsFromFile(File catalogFile) throws IOException {
        if (!catalogFile.exists()) return List.of();
        CatalogDto catalog = MAPPER.readValue(catalogFile, CatalogDto.class);
        return catalog.getVaults().stream().map(VaultDto::toDomain).toList();
    }

    /**
     * Persists the list of vaults to {@code catalog.json}.
     *
     * <p>Serialises via {@link Vault} → {@link VaultDto} → {@link CatalogDto}
     * to keep Jackson annotations out of the domain class. The file is written
     * with pretty-printing for human readability.</p>
     *
     * @param catalogFile the target {@code catalog.json} file
     * @param vaults     the current in-memory vault list
     * @throws IOException if the file cannot be written
     */
    public static void saveVaultsToFile(File catalogFile, List<Vault> vaults) throws IOException {
        CatalogDto catalog = new CatalogDto(
                vaults.stream().map(VaultDto::fromDomain).toList());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(catalogFile, catalog);
    }

    /**
     * Loads a {@code .vault} marker from the given file.
     *
     * @param file the marker file to read
     * @return the deserialized {@link VaultMarker}, or {@code null} if the file
     *         does not exist
     * @throws IOException if the file exists but cannot be read or parsed
     */
    public static VaultMarker loadVaultMarkerFromFile(File file) throws IOException {
        if (!file.exists()) return null;
        return MAPPER.readValue(file, VaultMarkerDto.class).toDomain();
    }

    /**
     * Writes a {@code .vault} marker to the given file, overwriting it if it
     * already exists.
     *
     * @param file   the destination marker file
     * @param marker the marker to persist
     * @throws IOException if the file cannot be written
     */
    public static void saveVaultMarkerToFile(File file, VaultMarker marker) throws IOException {
        MAPPER.writeValue(file, VaultMarkerDto.fromDomain(marker));
    }

    // ── Socket / event ────────────────────────────────────────────────────────

    /**
     * Deserialises a socket JSON message into a {@link SyncEvent}.
     *
     * <p>Parses via {@link SocketMessageDto} to avoid coupling Jackson to
     * {@link SyncEvent}. The {@code event} field is converted to
     * {@link EventType} via {@link EventType#valueOf(String)}.</p>
     *
     * @param json the JSON string received from the socket
     * @return the corresponding {@link SyncEvent}
     * @throws JsonProcessingException if the JSON is malformed
     * @throws IllegalArgumentException if {@code event} does not match any {@link EventType}
     */
    public static SyncEvent toSyncEvent(String json) throws JsonProcessingException {
        SocketMessageDto dto = MAPPER.readValue(json, SocketMessageDto.class);
        return new SyncEvent(EventType.valueOf(dto.getEvent()), dto.getVaultId());
    }
}