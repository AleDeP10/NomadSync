package io.aledep10.nomadsync.tray;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.aledep10.nomadsync.config.NomadProperties;
import io.aledep10.nomadsync.hook.NotificationHook;
import io.aledep10.nomadsync.orchestrator.*;
import io.aledep10.nomadsync.scheduler.AutosaveScheduler;
import io.aledep10.nomadsync.service.GitService;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.util.JsonMapper;
import io.aledep10.nomadsync.vault.Vault;
import io.aledep10.nomadsync.vault.VaultContext;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

/**
 * Accepts incoming TCP connections from {@link SocketClient} instances,
 * deserialises JSON messages into {@link SyncEvent} objects, and routes
 * them to the appropriate per-vault {@link SyncEventQueue}.
 *
 * <h2>Threading model</h2>
 * <ul>
 *   <li>{@code receiver} — blocks on {@link ServerSocket#accept()}, reads one JSON
 *       line per connection, responds with {@link SocketResponse}, and enqueues the
 *       parsed event on {@link #mainQueue}.</li>
 *   <li>{@code router} — blocks on {@link PriorityBlockingQueue#take()}, reads the
 *       highest-priority event from {@link #mainQueue}, and publishes it to the
 *       target vault queue.</li>
 *   <li>Per-vault orchestrator threads — one {@link SyncOrchestrator} per registered
 *       vault, started via {@link ScheduledExecutorService} in {@link #register(Vault)}.
 *       Managed via {@link ScheduledFuture} for clean cancellation at shutdown.</li>
 * </ul>
 *
 * <h2>Protocol</h2>
 * <p>Client sends a single JSON line. Server responds with one of:</p>
 * <ul>
 *   <li>{@link SocketResponse#ACK} — event parsed and enqueued successfully.</li>
 *   <li>{@link SocketResponse#NACK} — event type not recognised.</li>
 *   <li>{@link SocketResponse#ERROR} — JSON malformed.</li>
 * </ul>
 *
 * <h2>Broadcast events</h2>
 * <p>Events with {@code vaultId = null} are broadcast sentinels — the router
 * expands them into one targeted event per registered vault via
 * {@link SyncEvent#forVault(String)}. Used by {@link AutosaveScheduler} to
 * trigger autosave across all vaults without knowing the vault list at
 * publish time.</p>
 *
 * <h2>Configuration</h2>
 * <p>Requires {@link NomadProperties.Socket#PORT} to be set in the provided
 * {@link Properties}.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Construct — opens {@link ServerSocket} on the configured port.</li>
 *   <li>{@link #register(Vault)} — registers vaults and starts their orchestrators.</li>
 *   <li>{@link #start()} — starts the receiver and router threads.</li>
 *   <li>{@link #stop()} — cancels all orchestrator futures and interrupts all threads.</li>
 * </ol>
 *
 * <h2>Logging conventions</h2>
 * <p>A single {@code INFO} line announces each real mutation at the start —
 * opening the server socket (constructor), {@link #start()}, {@link #stop()},
 * and {@link #register(Vault)}. Once {@link #stop()} carries its own intro,
 * the router's own acknowledgement of the resulting interrupt is {@code DEBUG}
 * detail, not a second intro — same pattern as {@link SyncOrchestrator}.
 * Client-protocol anomalies (an unrecognised event type, malformed JSON) are
 * {@code WARN}, not ordinary flow. A received event and its subsequent routing
 * are two distinct, meaningful steps and both remain {@code INFO} — this is
 * not redundant intro/outro noise, it is two different actions completing.</p>
 */
public class SocketServer {

    final LogService                         logService;
    final GitService                         gitService;
    final NotificationHook                   notificationHook;
    final Map<String, VaultContext>          vaults;
    final PriorityBlockingQueue<SyncEvent>   mainQueue;
    final Thread                             receiver;
    final Thread                             router;
    final ServerSocket                       serverSocket;
    final ScheduledExecutorService           scheduler;

    /**
     * Constructs the server and opens the {@link ServerSocket} on the configured port.
     *
     * <p>Does not start any threads — call {@link #register(Vault)} for each vault,
     * then {@link #start()}.</p>
     *
     * <p>Logging: a single {@code INFO} line announces the port before the socket
     * is opened.</p>
     *
     * @param properties       application properties — must contain
     *                         {@link NomadProperties.Socket#PORT}
     * @param logService       shared logging service
     * @param gitService       stateless Git operations delegate
     * @param notificationHook hook invoked on unrecoverable failures
     * @throws IOException if the {@link ServerSocket} cannot be opened on the given port
     */
    public SocketServer(Properties properties, LogService logService,
                        GitService gitService, NotificationHook notificationHook)
            throws IOException {
        int port              = Integer.parseInt(
                properties.getProperty(NomadProperties.Socket.PORT));
        this.logService       = logService;
        this.gitService       = gitService;
        this.notificationHook = notificationHook;
        this.mainQueue        = new PriorityBlockingQueue<>();
        this.vaults           = new HashMap<>();
        this.scheduler        = Executors.newScheduledThreadPool(5);

        logService.info("SocketServer - opening server socket on port " + port);
        this.serverSocket     = new ServerSocket(port);

        this.receiver         = new Thread(this::doReceive,  "nomadsync-receiver");
        this.router           = new Thread(this::doRedirect, "nomadsync-router");
    }

    // ── Thread loops ──────────────────────────────────────────────────────────

    /**
     * Receiver loop — blocks on {@link ServerSocket#accept()}, reads one JSON line
     * via {@link BufferedReader#readLine()}, converts it to a {@link SyncEvent},
     * enqueues it on {@link #mainQueue}, and responds to the client.
     *
     * <p>Uses {@code readLine()} instead of Jackson's streaming API to avoid blocking
     * until EOF — the client signals end-of-message with a newline, which
     * {@code readLine()} detects immediately without waiting for connection close.</p>
     *
     * <p>Runs until the thread is interrupted. A single-connection error is logged
     * and the loop continues — the server stays alive.</p>
     *
     * <p>Logging: each successfully received event is announced at {@code INFO} —
     * one line per connection, driven by client/user action rather than a tight
     * poll, so this is not the kind of high-frequency noise the logging
     * conventions guard against. Client-protocol anomalies (unrecognised event
     * type, malformed JSON) are {@code WARN}.</p>
     */
    private void doReceive() {
        while (!Thread.currentThread().isInterrupted()) {
            Socket socket = null;
            PrintWriter writer = null;
            try {
                socket = serverSocket.accept();
                writer = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                String json = reader.readLine();
                SyncEvent event = JsonMapper.toSyncEvent(json);
                logService.info("Received event: " + event);
                mainQueue.offer(event);
                writer.println(SocketResponse.ACK.name());
            } catch (IllegalArgumentException e) {
                logService.warn("Unable to recognise event type: " + e.getMessage());
                if (writer != null) writer.println(SocketResponse.NACK.name());
                else logService.error("Unable to send NACK - writer not initialised");
            } catch (JsonProcessingException e) {
                logService.warn("Malformed JSON message: " + e.getMessage());
                if (writer != null) writer.println(SocketResponse.ERROR.name());
                else logService.error("Unable to send ERROR - writer not initialised");
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    logService.error("Error while accepting connection or parsing event", e);
                }
            } finally {
                if (socket != null && !socket.isClosed()) {
                    try { socket.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    /**
     * Router loop — blocks on {@link PriorityBlockingQueue#take()}, reads the
     * highest-priority event from {@link #mainQueue}, and routes it.
     *
     * <p>If {@code event.getVaultId()} is {@code null}, the event is a broadcast
     * sentinel — expanded into one targeted event per registered vault via
     * {@link SyncEvent#forVault(String)}. Otherwise, routed to the specific vault.</p>
     *
     * <p>Uses {@code take()} instead of {@code poll()} + sleep — no busy-waiting.</p>
     *
     * <p>Logging: the interrupt that ends this loop is the outcome of {@link #stop()},
     * which already carries its own {@code INFO} intro — so this is logged at
     * {@code DEBUG}, not as a second intro.</p>
     */
    private void doRedirect() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                SyncEvent event = mainQueue.take();
                if (event.getVaultId() == null) {
                    vaults.forEach((vaultId, ctx) ->
                            publishEvent(ctx, event.forVault(vaultId)));
                } else {
                    publishEvent(vaults.get(event.getVaultId()), event);
                }
            } catch (InterruptedException e) {
                logService.debug("Router interrupted, shutting down.");
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Publishes an event to the given vault context's queue.
     *
     * <p>Logs a warning and returns silently if {@code ctx} is {@code null} —
     * this can happen if a targeted event arrives for an unregistered vault.</p>
     *
     * <p>Logging: a successful routing is announced at {@code INFO} — a distinct,
     * meaningful step from "event received" ({@link #doReceive()}), not redundant
     * outro noise. An unregistered target vault is an anomaly, logged at {@code WARN}.</p>
     */
    private void publishEvent(VaultContext ctx, SyncEvent event) {
        if (ctx == null) {
            logService.warn("No vault registered for id: " + event.getVaultId());
            return;
        }
        logService.info("Event %s routed to vault %s [%s]"
                .formatted(event, ctx.vault().getName(), ctx.vault().getId()));
        ctx.queue().publish(event);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts the receiver and router threads.
     *
     * <p>Vaults registered before this call are already running — their orchestrators
     * were scheduled in {@link #register(Vault)}. Vaults registered after this call
     * are also supported.</p>
     *
     * <p>Logging: a single {@code INFO} line announces the operation at the start.</p>
     */
    public void start() {
        logService.info("start - starting receiver and router threads");
        receiver.start();
        router.start();
    }

    /**
     * Stops all orchestrator futures, interrupts receiver and router threads,
     * and shuts down the scheduler.
     *
     * <p>{@link ScheduledFuture#cancel(boolean) cancel(true)} sends an interrupt to
     * each orchestrator thread. The receiver and router exit on the next iteration
     * of their loop guard.</p>
     *
     * <p>Logging: a single {@code INFO} line announces the shutdown request at the
     * start — the router's own acknowledgement of the resulting interrupt is
     * {@code DEBUG} detail, not a second intro.</p>
     */
    public void stop() {
        logService.info("stop - shutting down socket server");
        vaults.values().stream()
                .map(VaultContext::aggregatorFuture)
                .forEach(f -> f.cancel(true));
        receiver.interrupt();
        router.interrupt();
        scheduler.shutdown();
    }

    /**
     * Registers a {@link Vault} with the server.
     *
     * <p>Creates a dedicated {@link SyncEventQueue} and {@link SyncOrchestrator},
     * wraps them in a {@link VaultContext}, and schedules the orchestrator to start
     * immediately. Safe to call before or after {@link #start()}.</p>
     *
     * <p>Logging: a single {@code INFO} line announces the operation at the start.</p>
     *
     * @param vault the vault to register
     */
    public void register(Vault vault) {
        logService.info("register - " + vault.getRepoSlug()
                + " - registering vault and starting orchestrator");
        SyncEventQueue   queue        = new SyncEventQueue(logService);
        SyncOrchestrator orchestrator = new SyncOrchestrator(
                vault, gitService, logService, queue, notificationHook);
        ScheduledFuture<?> aggregatorFuture = scheduler.schedule(
                orchestrator::start, 0, TimeUnit.MILLISECONDS);
        vaults.put(vault.getId(),
                new VaultContext(vault, queue, orchestrator, aggregatorFuture));
    }

    /**
     * Publishes a {@link SyncEvent} directly to the per-vault queue, bypassing the
     * socket layer.
     *
     * <p>Intended for same-JVM callers (e.g. tray icon left-click SYNCHRONIZE) that
     * do not need the TCP round-trip.</p>
     *
     * <p>Logging: no log of its own — delegates entirely to {@link #publishEvent},
     * whose {@code INFO}/{@code WARN} lines already cover observability, the same
     * pattern as {@code forSnapshot} delegating to {@code load()} in
     * {@link io.aledep10.nomadsync.service.GitignoreService}.</p>
     *
     * @param vaultId the target vault identifier
     * @param event   the event to publish
     * @throws IllegalArgumentException if {@code vaultId} is {@code null} or not registered
     */
    public void publish(String vaultId, SyncEvent event) {
        if (vaultId == null || !vaults.containsKey(vaultId)) {
            throw new IllegalArgumentException("Vault not found for id: " + vaultId);
        }
        publishEvent(vaults.get(vaultId), event);
    }
}