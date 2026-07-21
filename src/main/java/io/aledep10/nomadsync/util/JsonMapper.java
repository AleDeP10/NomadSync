package io.aledep10.nomadsync.util;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aledep10.nomadsync.dto.SocketMessageDto;
import io.aledep10.nomadsync.dto.VaultDto;
import io.aledep10.nomadsync.dto.VaultMarkerDto;
import io.aledep10.nomadsync.dto.VaultRootDto;
import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.orchestrator.SyncEvent;
import io.aledep10.nomadsync.vault.Vault;
import io.aledep10.nomadsync.marker.VaultMarker;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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

    // ── Vault persistence ─────────────────────────────────────────────────────

    /**
     * Loads the list of vaults from {@code catalog.json}.
     *
     * <p>Deserialises via {@link VaultRootDto} → {@link VaultDto} → {@link Vault}
     * to keep Jackson annotations out of the domain class. Returns an empty list
     * if the file does not exist — no exception is thrown.</p>
     *
     * @param file the {@code catalog.json} file
     * @return list of domain {@link Vault} objects, or empty list if file absent
     * @throws IOException if the file exists but cannot be read or parsed
     */
    public static List<Vault> loadVaultsFromFile(File file) throws IOException {
        if (!file.exists()) return List.of();
        VaultRootDto root = MAPPER.readValue(file, VaultRootDto.class);
        return root.vaults().stream().map(VaultDto::toDomain).toList();
    }

    /**
     * Persists the list of vaults to {@code catalog.json}.
     *
     * <p>Serialises via {@link Vault} → {@link VaultDto} → {@link VaultRootDto}
     * to keep Jackson annotations out of the domain class. The file is written
     * with pretty-printing for human readability.</p>
     *
     * @param vaultsFile the target {@code catalog.json} file
     * @param vaults     the current in-memory vault list
     * @throws IOException if the file cannot be written
     */
    public static void saveVaultsToFile(File vaultsFile, List<Vault> vaults) throws IOException {
        VaultRootDto root = new VaultRootDto(
                vaults.stream().map(VaultDto::fromDomain).toList());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(vaultsFile, root);
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

    /**
     * Reads an {@link InputStream} and returns its content as a compact JSON string.
     *
     * <p>Uses Jackson's streaming API to parse without loading the full payload
     * into a string first. The result is re-serialised as a compact JSON string
     * suitable for passing to {@link #toSyncEvent(String)}.</p>
     *
     * @param stream the input stream to read — typically a socket input stream
     * @return the JSON content as a compact string
     * @throws IOException if the stream cannot be read or is not valid JSON
     */
    public static String extractJson(InputStream stream) throws IOException {
        JsonFactory factory = MAPPER.getFactory();
        try (JsonParser parser = factory.createParser(stream)) {
            JsonNode node = MAPPER.readTree(parser);
            return node.toString();
        }
    }

    // ── Generic ───────────────────────────────────────────────────────────────

    /**
     * Serialises any object to a compact JSON string.
     *
     * <p>General-purpose fallback — use for socket transmission, logging, and any
     * other context where a JSON string representation is needed. Covers all cases
     * previously handled by the removed per-type overloads ({@code toJson(Vault)},
     * {@code toJson(SocketMessage)}).</p>
     *
     * @param obj the object to serialise
     * @return compact JSON string
     * @throws JsonProcessingException if serialisation fails
     */
    public static String toJson(Object obj) throws JsonProcessingException {
        return MAPPER.writeValueAsString(obj);
    }
}