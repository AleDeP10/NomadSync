package io.aledep10.nomadsync.tray;

import io.aledep10.nomadsync.exception.NomadSyncException;
import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.logging.LogLevel;
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
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/**
 * Unit tests for {@link SocketClient}.
 *
 * <p>Each test spins up a lightweight in-process server on a random OS-assigned port,
 * avoiding port conflicts between concurrent test runs. Server behaviour is defined
 * per-test via {@link #serverBehavior} and started via {@link #startServer()}.</p>
 *
 * <p>{@code serverBehavior} must be assigned before calling {@link #startServer()} —
 * the setUp phase only allocates the port; the server thread is started explicitly
 * by each test after configuring its expected behaviour.</p>
 */
class SocketClientTest {

    static LogService logService;
    ServerSocket serverSocket;
    SocketClient socketClient;

    /**
     * Pluggable server behaviour — assigned by each test before calling {@link #startServer()}.
     * Runs on a dedicated thread to avoid blocking the test thread on {@code accept()}.
     */
    Runnable serverBehavior;

    @BeforeAll
    static void prepareLogService() throws IOException {
        TestVault testVault = TestUtil.getTestVault("SocketClientTest");
        logService = new LogService(
                TestUtil.forLogService(
                        testVault, LogLevel.DEBUG), testVault.rootPath());
    }

    @BeforeEach
    void setUp() throws IOException {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        socketClient = new SocketClient(TestUtil.forClient(port), logService);
    }

    @AfterEach
    void tearDown() throws IOException {
        serverSocket.close();
    }

    /**
     * Starts the server thread with the behaviour defined in {@link #serverBehavior}.
     * Must be called after assigning {@code serverBehavior} and before {@code send()}.
     */
    void startServer() {
        new Thread(serverBehavior).start();
    }

    // ── tests ─────────────────────────────────────────────────────────────────
    @Test
    void socketClient_sendsEventAndReceivesAck() throws NomadSyncException, InterruptedException {
        serverBehavior = () -> respondWith(SocketResponse.ACK);
        startServer();

        socketClient.send(EventType.PULL_LOGON.name(), UUID.randomUUID().toString());
        // no assertion needed — send() completing without exception verifies the contract
    }

    @Test
    void socketClient_retriesOnNack_succeedsEventually() {

    }

    @Test
    void socketClient_exhaustsRetriesOnNack_throwsException() {

    }

    @Test
    void socketClient_serverRespondsError_throwsException() {

    }

    @Test
    void socketClient_connectionRefused_retriesAndSucceeds() {

    }


    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Accepts one connection, discards the incoming message, and responds with the given response.
     */
    private void respondWith(SocketResponse response) {
        try {
            Socket client = serverSocket.accept();
            readAndRespond(client, response);
        } catch (IOException e) {
            // tearDown closed the server — expected
        }
    }

    /**
     * Reads one line from the client and replies with the given response.
     */
    private void readAndRespond(Socket client, SocketResponse response) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
        reader.readLine();
        PrintWriter writer = new PrintWriter(client.getOutputStream(), true);
        writer.println(response.name());
        client.close();
    }
}
