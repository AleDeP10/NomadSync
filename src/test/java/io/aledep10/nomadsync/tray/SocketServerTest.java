package io.aledep10.nomadsync.tray;

import io.aledep10.nomadsync.hook.NotificationHook;
import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.orchestrator.Vault;
import io.aledep10.nomadsync.orchestrator.VaultContext;
import io.aledep10.nomadsync.service.GitService;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.util.TestUtil;
import io.aledep10.nomadsync.util.TestVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Integration tests for {@link SocketServer}.
 *
 * <p>Each test uses a real {@link SocketServer} on an OS-assigned port.
 * Three vaults are pre-registered in {@code @BeforeAll} and the server is
 * started in {@code @BeforeEach}. The server is stopped in {@code @AfterEach}.</p>
 *
 * <p>{@link GitService} is mocked — no real Git operations are performed.
 * {@link org.awaitility.Awaitility} is used for async assertions on mock interactions.</p>
 */
class SocketServerTest {

    static TestVault testVault;
    static LogService logService;
    static Map<String, Vault> testVaults;
    static GitService gitService;

    NotificationHook notificationHook;
    SocketServer socketServer;
    int port;

    @BeforeAll
    static void prepareSharedState() throws IOException {
        testVault  = TestUtil.getTestVault("SocketServerTest");
        logService = new LogService(TestUtil.forLogService(testVault, LogLevel.DEBUG));
        gitService = mock(GitService.class);

        testVaults = new HashMap<>();
        testVaults.put("test-1", new Vault(UUID.randomUUID().toString(), "test-1",
                testVault.vaultPath().resolve("test-1").toString()));
        testVaults.put("test-2", new Vault(UUID.randomUUID().toString(), "test-2",
                testVault.vaultPath().resolve("test-2").toString()));
        testVaults.put("test-3", new Vault(UUID.randomUUID().toString(), "test-3",
                testVault.vaultPath().resolve("test-3").toString()));
    }

    @BeforeEach
    void setUp() throws IOException {
        notificationHook = mock(NotificationHook.class);

        Properties serverProperties = TestUtil.forServer();
        port = Integer.parseInt(serverProperties.getProperty("socket.port"));
        socketServer = new SocketServer(serverProperties, logService, gitService, notificationHook);

        socketServer.start();
        testVaults.values().forEach(socketServer::register);
    }

    @AfterEach
    void tearDown() throws IOException {
        socketServer.stop();
        TestUtil.cleanup(testVault);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    /**
     * Verifies the end-to-end happy path: a client sends a {@code SYNCHRONIZE} event,
     * the server receives it, routes it to the orchestrator of the target vault,
     * and the orchestrator delegates to {@link GitService}.
     *
     * <p><strong>Assert target</strong>: {@code gitService.synchronize(vault.getPath())} —
     * the orchestrator receives {@code vaultPath} from {@link VaultContext},
     * not from the event.</p>
     *
     * <p>Uses {@link org.awaitility.Awaitility} because the orchestrator runs on a
     * separate thread — the assert must poll until the interaction is recorded
     * or the timeout expires.</p>
     */
    @Test
    void socketServer_receivesEventAndPublishesToQueue() throws IOException {
        Vault vault = testVaults.get("test-1");
        SocketMessage message = new SocketMessage(
                EventType.SYNCHRONIZE.name(), vault.getId(), 10);

        try (Socket socket = new Socket("localhost", port)) {
            new PrintWriter(socket.getOutputStream(), true).println(message);
            new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine();
        }

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gitService).synchronize(vault.getPath()));
    }

    /**
     * Verifies that the server responds {@code ACK} for a valid event.
     */
    @Test
    void socketServer_respondsAckOnValidEvent() throws IOException {
        Vault vault = testVaults.get("test-3");
        SocketMessage message = new SocketMessage(
                EventType.SYNCHRONIZE.name(), vault.getId(), 10);

        try (Socket socket = new Socket("localhost", port)) {
            new PrintWriter(socket.getOutputStream(), true).println(message);
            String response = new BufferedReader(
                    new InputStreamReader(socket.getInputStream())).readLine();

            assertThat(response).isEqualTo(SocketResponse.ACK.name());
        }
    }

    // ── Error handling ────────────────────────────────────────────────────────

    /**
     * Verifies that an unknown event type returns {@code NACK} and nothing is
     * published to any vault queue.
     */
    @Test
    void socketServer_receivesUnknownEventType_respondsNack() throws IOException {
        Vault vault = testVaults.get("test-2");
        SocketMessage message = new SocketMessage("unexisting_event", vault.getId(), 10);

        try (Socket socket = new Socket("localhost", port)) {
            new PrintWriter(socket.getOutputStream(), true).println(message);
            String response = new BufferedReader(
                    new InputStreamReader(socket.getInputStream())).readLine();

            assertThat(response).isEqualTo(SocketResponse.NACK.name());
            assertThat(socketServer.mainQueue.size()).isEqualTo(0);
            assertThat(socketServer.vaults.values().stream()
                    .noneMatch(v -> v.getQueue().size() > 0)).isTrue();
        }
    }

    /**
     * Verifies that a malformed JSON message returns {@code ERROR}.
     */
    @Test
    void socketServer_ignoresMalformedJson() throws IOException {
        String malformed = "{\"event\": \"SYNCHRONIZE\", timestamp 123, \"retryCount\": 0}";

        try (Socket socket = new Socket("localhost", port)) {
            new PrintWriter(socket.getOutputStream(), true).println(malformed);
            String response = new BufferedReader(
                    new InputStreamReader(socket.getInputStream())).readLine();

            assertThat(response).isEqualTo(SocketResponse.ERROR.name());
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Verifies that {@code stop()} terminates cleanly — no events remain in any queue.
     */
    @Test
    void socketServer_stopsCleanly() {
        socketServer.stop();

        assertThat(socketServer.mainQueue.size()).isEqualTo(0);
        assertThat(socketServer.vaults.values().stream()
                .noneMatch(v -> v.getQueue().size() > 0)).isTrue();
    }

    /**
     * Verifies that the server handles multiple sequential requests without errors.
     */
    @Test
    void socketServer_handlesMultipleSequentialRequests() {
        List<SocketMessage> messages = List.of(
                new SocketMessage(EventType.PULL_LOGON.name(),  testVaults.get("test-1").getId(), 10),
                new SocketMessage(EventType.PULL_LOGON.name(),  testVaults.get("test-2").getId(), 10),
                new SocketMessage(EventType.PULL_LOGON.name(),  testVaults.get("test-3").getId(), 10),
                new SocketMessage(EventType.SYNCHRONIZE.name(), testVaults.get("test-2").getId(), 10),
                new SocketMessage(EventType.AUTOSAVE.name(),    null,                             10),
                new SocketMessage(EventType.SYNCHRONIZE.name(), testVaults.get("test-3").getId(), 10));

        messages.forEach(message -> {
            try (Socket socket = new Socket("localhost", port)) {
                new PrintWriter(socket.getOutputStream(), true).println(message);
                String response = new BufferedReader(
                        new InputStreamReader(socket.getInputStream())).readLine();
                logService.info("Response for %s: %s".formatted(message, response));
            } catch (IOException e) {
                logService.error("Error sending message: " + message, e);
            }
        });
    }
}