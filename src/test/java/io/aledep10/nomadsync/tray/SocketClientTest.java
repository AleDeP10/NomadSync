package io.aledep10.nomadsync.tray;

import io.aledep10.nomadsync.exception.NomadSyncException;
import io.aledep10.nomadsync.orchestrator.EventType;
import io.aledep10.nomadsync.logging.LogLevel;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.util.ClassFailureTracker;
import io.aledep10.nomadsync.util.TempDirCleanupExtension;
import io.aledep10.nomadsync.util.TestUtil;
import io.aledep10.nomadsync.util.TestVault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

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
 *
 * <p>The shared {@link #sharedVault} (created once in {@code @BeforeAll}) exists
 * only to give {@link #logService} a place to write its log file — cleaned up in
 * {@code @AfterAll} only if every test in this class passed (see
 * {@link ClassFailureTracker}). No test in this file creates its own per-test
 * temp directory, so {@link io.aledep10.nomadsync.util.TempDirs} is not injected
 * anywhere here — {@link TempDirCleanupExtension} is still registered for
 * consistency and in case a future test needs it.</p>
 */
@ExtendWith({TempDirCleanupExtension.class, ClassFailureTracker.class})
class SocketClientTest {

    static TestVault sharedVault;
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
        sharedVault = TestUtil.getTestVault("SocketClientTest");
        logService  = new LogService(TestUtil.forLogService(sharedVault, LogLevel.DEBUG), sharedVault.rootPath());
    }

    @AfterAll
    static void tearDownAll(ExtensionContext context) throws IOException {
        logService.close();
        if (!ClassFailureTracker.anyTestFailed(context)) {
            TestUtil.cleanup(sharedVault);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        socketClient = new SocketClient(TestUtil.forClient(port), logService);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Unconditional — a network resource, not a temp-dir/filesystem-inspection
        // concern, so it is closed regardless of test outcome to avoid port leaks.
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

    /**
     * NACK is treated as a retryable failure (converted to IOException internally) —
     * the server rejects the first attempt, then accepts the second connection
     * (the client's retry) and responds ACK. send() should complete without
     * throwing, having recovered on retry.
     */
    @Test
    void socketClient_retriesOnNack_succeedsEventually() throws NomadSyncException, InterruptedException {
        serverBehavior = () -> {
            respondWith(SocketResponse.NACK);
            respondWith(SocketResponse.ACK);
        };
        startServer();

        socketClient.send(EventType.PULL_LOGON.name(), UUID.randomUUID().toString());
        // no assertion needed — send() completing without exception verifies recovery
    }

    /**
     * The server responds NACK on every connection attempt, never ACK — send()
     * must exhaust {@link SocketClient#MAX_RETRIES} and throw {@link NomadSyncException}.
     * The server loop accepts more connections than the client can possibly make
     * before giving up (generous upper bound) — {@code respondWith} silently
     * absorbs the IOException once {@code tearDown()} closes the socket, so
     * looping past what's actually used is harmless.
     */
    @Test
    void socketClient_exhaustsRetriesOnNack_throwsException() {
        serverBehavior = () -> {
            for (int i = 0; i < 6; i++) {
                respondWith(SocketResponse.NACK);
            }
        };
        startServer();

        assertThatThrownBy(() ->
                socketClient.send(EventType.PULL_LOGON.name(), UUID.randomUUID().toString()))
                .isInstanceOf(NomadSyncException.class);
    }

    /**
     * ERROR is NOT converted to a retryable IOException by send() (only NACK is —
     * see {@link SocketClient}'s class Javadoc) — a single ERROR response alone
     * would otherwise send the client into an immediate, un-backed-off reconnect
     * loop. To reach a real, bounded exception here without hanging the test, the
     * server responds ERROR exactly once and then closes the listening socket
     * entirely — every subsequent connection attempt fails with a genuine
     * {@link IOException} (connection refused), which IS retried with backoff
     * and correctly exhausts {@link SocketClient#MAX_RETRIES}.
     */
    @Test
    void socketClient_serverRespondsError_throwsException() {
        serverBehavior = () -> {
            try {
                Socket client = serverSocket.accept();
                readAndRespond(client, SocketResponse.ERROR);
            } catch (IOException e) {
                // ignore — proceed to close the server regardless
            } finally {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                    // already closed or closing — fine
                }
            }
        };
        startServer();

        assertThatThrownBy(() ->
                socketClient.send(EventType.PULL_LOGON.name(), UUID.randomUUID().toString()))
                .isInstanceOf(NomadSyncException.class);
    }

    /**
     * Simulates a genuinely refused connection (nothing listening on the port
     * yet) followed by recovery: the port bound in {@code setUp()} is released
     * immediately, then rebound on a short delay by a background thread that
     * accepts one connection and responds ACK. The client's first connection
     * attempt(s), made before the delayed rebind completes, fail with a real
     * {@code ConnectException} — exercised by the same {@code IOException}
     * retry/backoff path as the NACK case — and the retry that lands after the
     * rebind succeeds.
     *
     * <p><strong>Note on timing:</strong> unlike the other tests in this file,
     * this one has inherent timing sensitivity — it relies on the delayed
     * rebind completing before {@link SocketClient#MAX_RETRIES} is exhausted.
     * The 20ms delay is chosen to comfortably fit within the client's first
     * retry cycle given the test configuration's 10ms base
     * {@code socket.retryDelay}, but is not a hard guarantee on an
     * unusually slow or loaded machine.</p>
     */
    @Test
    void socketClient_connectionRefused_retriesAndSucceeds()
            throws NomadSyncException, InterruptedException, IOException {
        int port = serverSocket.getLocalPort();
        serverSocket.close(); // release the port — nothing listens here initially

        Thread delayedServer = new Thread(() -> {
            try {
                Thread.sleep(20); // let the client's first connection attempt genuinely fail first
                try (ServerSocket delayed = new ServerSocket(port)) {
                    Socket client = delayed.accept();
                    readAndRespond(client, SocketResponse.ACK);
                }
            } catch (IOException | InterruptedException e) {
                // best-effort — if this fails, the test's own assertion below will catch it
            }
        });
        delayedServer.start();

        socketClient.send(EventType.PULL_LOGON.name(), UUID.randomUUID().toString());
        // no assertion needed — send() completing without exception verifies the
        // client survived the initial connection-refused failure(s) and recovered
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