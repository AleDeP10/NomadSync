package io.aledep10.nomadsync.util;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aledep10.nomadsync.dto.SocketMessageDto;
import io.aledep10.nomadsync.dto.VaultDto;
import io.aledep10.nomadsync.dto.VaultRootDto;
import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.orchestrator.SyncEvent;
import io.aledep10.nomadsync.orchestrator.Vault;
import io.aledep10.nomadsync.tray.SocketMessage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * JSON serialisation and deserialisation utility for ObsidianSync domain objects.
 *
 * <p>Acts as the mapping layer between the socket transport format (JSON strings)
 * and the domain model ({@link SyncEvent}, {@link Vault}). Keeps Jackson awareness
 * out of domain classes — {@link SyncEvent} and {@link Vault} do not depend on
 * Jackson annotations except where necessary for {@code @JsonCreator}.</p>
 *
 * <p>A single {@link ObjectMapper} instance is shared across all calls — it is
 * thread-safe after initial configuration and expensive to instantiate per call.</p>
 *
 * <p>This class is not meant to be instantiated — all members are {@code static}.</p>
 */
public final class JsonMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private JsonMapper() {
        // utility class — no instances
    }

    /**
     * Carica la lista di Vault deserializzando il wrapper DTO.
     */
    public static List<Vault> loadVaultsFromFile(File file) throws IOException {
        if (!file.exists()) return List.of();
        VaultRootDto root = MAPPER.readValue(file, VaultRootDto.class);
        return root.getVaults().stream().map(VaultDto::asVault).toList();
    }

    /**
     * Salva la lista di Vault nel file vaults.json usando il wrapper DTO.
     */
    public static void saveVaultsToFile(File vaultsFile, List<Vault> vaults) throws IOException {
        VaultRootDto root = new VaultRootDto(vaults.stream().map(VaultDto::new).toList());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(vaultsFile, root);
    }

    /**
     * Serializzazione generica per socket o log.
     */
    public static String toJson(Object obj) throws JsonProcessingException {
        return MAPPER.writeValueAsString(obj);
    }

    /**
     * Reads an {@link InputStream} and returns its content as a compact JSON string.
     *
     * <p>Uses Jackson's streaming API to parse the input without loading the full
     * payload into a string first. The result is re-serialised as a compact
     * (non-pretty) JSON string suitable for passing to other mapper methods.</p>
     *
     * @param stream the input stream to read from — typically a socket input stream
     * @return the JSON content as a compact string
     * @throws IOException if the stream cannot be read or the content is not valid JSON
     */
    public static String extractJson(InputStream stream) throws IOException {
        JsonFactory factory = MAPPER.getFactory();
        try (JsonParser parser = factory.createParser(stream)) {
            JsonNode node = MAPPER.readTree(parser);
            return node.toString();
        }
    }

    /**
     * Deserialises a JSON string into a {@link Vault} object.
     *
     * <p>The JSON must contain {@code id}, {@code name}, and {@code path} fields
     * matching the {@link Vault} constructor annotated with {@code @JsonCreator}.</p>
     *
     * @param json the JSON string to deserialise
     * @return the deserialised {@link Vault}
     * @throws JsonProcessingException if the JSON is malformed or missing required fields
     */
    public static Vault toVault(String json) throws JsonProcessingException {
        // [TODO] ancora non utilizzato
        return MAPPER.readValue(json, Vault.class);
    }

    /**
     * Deserialises a socket JSON message into a {@link SyncEvent}.
     *
     * <p>Parses via {@link SocketMessageDto} (transport layer) to avoid
     * coupling Jackson annotations to the {@link SyncEvent} domain class.
     * The {@code event} field is converted to {@link EventType} via
     * {@link EventType#valueOf(String)}.</p>
     *
     * @param json the JSON string received from the socket
     * @return the corresponding {@link SyncEvent}
     * @throws JsonProcessingException if the JSON is malformed
     * @throws IllegalArgumentException if the {@code event} field does not match any {@link EventType}
     */
    public static SyncEvent toSyncEvent(String json) throws JsonProcessingException {
        SocketMessageDto dto = MAPPER.readValue(json, SocketMessageDto.class);
        return new SyncEvent(EventType.valueOf(dto.getEvent()), dto.getVaultId());
    }

    /**
     * Serialises a {@link SocketMessage} to a compact JSON string for socket transmission.
     *
     * @param message the message to serialise
     * @return compact JSON string
     * @throws JsonProcessingException if serialisation fails
     */
    public static String toJson(SocketMessage message) throws JsonProcessingException {
        return MAPPER.writeValueAsString(message);
    }

    /**
     * Serialises a {@link Vault} to a compact JSON string for persistence in
     * {@code vaults.json}.
     *
     * @param vault the vault to serialise
     * @return compact JSON string
     * @throws JsonProcessingException if serialisation fails
     */
    public static String toJson(Vault vault) throws JsonProcessingException {
        return MAPPER.writeValueAsString(vault);
    }
}
