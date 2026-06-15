package io.aledep10.nomadsync.tray;

import io.aledep10.nomadsync.exception.NomadSyncException;
import io.aledep10.nomadsync.service.LogService;

import java.io.*;
import java.net.Socket;
import java.util.Properties;

/**
 * Sends synchronisation events to the {@link SocketServer} via a local TCP socket.
 *
 * <p>Each call to {@link #send(String, String)} opens a new connection, transmits
 * a JSON-encoded {@link SocketMessage}, and waits for a {@link SocketResponse#ACK}.</p>
 *
 * <p>On {@link IOException} or {@link SocketResponse#NACK}, applies exponential backoff
 * retry up to {@link #MAX_RETRIES} attempts before throwing {@link NomadSyncException}.
 * Delay progression driven by {@link SocketMessage#incrementRetry()}.</p>
 *
 * <p>NACK is treated as a retryable failure by converting it to an {@link IOException} —
 * this reuses the existing retry mechanism without duplicating backoff logic.</p>
 */
@SuppressWarnings("BusyWait")
public class SocketClient {

    public static final int MAX_RETRIES = 3;

    private final String host;
    private final int port;
    private final long retryDelay;
    private final LogService logService;

    /**
     * Constructs a SocketClient from the provided configuration.
     *
     * @param properties application properties containing {@code socket.host},
     *                   {@code socket.port} and {@code socket.retryDelay}
     * @param logService shared logging service
     */
    public SocketClient(Properties properties, LogService logService) {
        this.host       = properties.getProperty("socket.host", "localhost");
        this.port       = Integer.parseInt(properties.getProperty("socket.port"));
        this.retryDelay = Long.parseLong(properties.getProperty("socket.retryDelay"));
        this.logService = logService;
    }

    /**
     * Sends a synchronisation event to the socket server.
     *
     * <p>Opens a TCP connection, serialises the event as JSON, and waits for
     * a {@link SocketResponse#ACK}. Retries on {@link IOException} or
     * {@link SocketResponse#NACK} with exponential backoff.
     * Throws after {@link #MAX_RETRIES} failed attempts.</p>
     *
     * @param eventType the event type name (e.g. {@code "PULL_LOGON"})
     * @param vaultId   the target vault identifier
     * @throws NomadSyncException if the event cannot be delivered after all retries
     * @throws InterruptedException  if the thread is interrupted during a retry delay
     */
    public void send(String eventType, String vaultId)
            throws NomadSyncException, InterruptedException {
        SocketMessage message = new SocketMessage(eventType, vaultId, retryDelay);
        String result = null;
        Socket socket = null;

        do {
            try {
                socket = new Socket(host, port);

                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                writer.println(message);

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                result = reader.readLine();
                logService.info("Server response: " + result);

                // NACK treated as retryable failure — reuses the IOException retry path
                if (result != null && result.equals(SocketResponse.NACK.name())) {
                    throw new IOException("NACK received from server");
                }

            } catch (IOException e) {
                if (message.getRetryCount() >= MAX_RETRIES) {
                    logService.warn("Max retries reached — event discarded: " + eventType);
                    throw new NomadSyncException(
                            "Error sending event %s for vault %s".formatted(eventType, vaultId), e);
                }
                message.incrementRetry();
                logService.warn("Retry %d/%d for %s in %ds".formatted(
                        message.getRetryCount(), MAX_RETRIES,
                        eventType, message.getRetryDelay() / 1000));
                // exponential backoff — intentional sleep between retries
                Thread.sleep(message.getRetryDelay());

            } finally {
                if (socket != null && !socket.isClosed()) {
                    try { socket.close(); } catch (IOException ignored) {}
                }
            }
        } while (result == null || SocketResponse.valueOf(result) != SocketResponse.ACK);
    }
}
