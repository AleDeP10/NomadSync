package io.aledep10.nomadsync.logging;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Sends log events to the Seq structured log server via HTTP in
 * CLEF (Compact Log Event Format).
 *
 * <h2>Threading model</h2>
 * <p>The caller ({@link io.aledep10.nomadsync.service.LogService}) never blocks —
 * events are offered to an internal {@link BlockingQueue} and return immediately.
 * A dedicated daemon thread ({@code seq-log-writer}) consumes from the queue and
 * performs the HTTP POST in the background.</p>
 *
 * <p>The worker is a <em>daemon</em> thread: if the JVM exits unexpectedly without
 * {@link #close()} being called, the daemon is terminated automatically rather than
 * keeping the process alive indefinitely.</p>
 *
 * <h2>Backpressure</h2>
 * <p>The queue capacity is capped at {@value MAX_QUEUE_SIZE} events. If the queue
 * is full (e.g. Seq is unreachable for an extended period), incoming events are
 * silently dropped and a warning is written to {@code stderr}. This prevents the
 * logging subsystem from blocking application threads under load.</p>
 *
 * <h2>Shutdown</h2>
 * <p>{@link #close()} clears the queue, inserts a {@link #POISON_PILL} sentinel,
 * and waits up to {@value DRAIN_TIMEOUT_SECONDS} seconds for the worker to process
 * any in-flight events before terminating. If the worker is still alive after the
 * timeout, it is interrupted.</p>
 *
 * <h2>HTTP failure handling</h2>
 * <p>If a POST returns a non-2xx status or throws {@link IOException}, the failure
 * is logged to {@code stderr} and the worker continues — the event is lost but
 * subsequent events are unaffected.</p>
 */
public class SeqHttpLogWriter implements LogWriter {

    private static final String POISON_PILL          = "__SHUTDOWN__";
    private static final int    MAX_QUEUE_SIZE        = 1000;
    private static final int    DRAIN_TIMEOUT_SECONDS = 5;

    private final BlockingQueue<String> queue     = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private final Thread                worker;
    private final HttpClient            client    = HttpClient.newHttpClient();
    private final ClefFormatter         formatter = new ClefFormatter();
    private final String                seqUrl;
    private final String                apiKey;

    /**
     * Constructs the writer and immediately starts the background daemon thread.
     *
     * @param seqUrl base URL of the Seq server (e.g. {@code http://localhost:5341})
     * @param apiKey Seq API key, or an empty string if authentication is disabled
     */
    public SeqHttpLogWriter(String seqUrl, String apiKey) {
        this.seqUrl = seqUrl;
        this.apiKey = apiKey;
        worker = new Thread(this::workerLoop, "seq-log-writer");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Enqueues a CLEF-formatted log event for asynchronous delivery to Seq.
     *
     * <p>This method never blocks. If the internal queue is full, the event is
     * dropped and a warning is written to {@code stderr}.</p>
     *
     * @param level       severity level of the event
     * @param universalId UVL of the originating vault, or {@code "SYSTEM"}
     * @param message     the log message
     * @param cause       exception to include as {@code @x}, or {@code null}
     */
    @Override
    public void write(LogLevel level, String universalId, String message, Throwable cause) {
        String clef = formatter.format(level, universalId, message, cause).getFirst();
        if (!queue.offer(clef)) {
            System.err.println("[SeqHttpLogWriter] queue full — event dropped: " + message);
        }
    }

    /**
     * Initiates an orderly shutdown of the background writer thread.
     *
     * <p>Clears any queued events, inserts a {@link #POISON_PILL} sentinel to signal
     * the worker to stop, and waits up to {@value DRAIN_TIMEOUT_SECONDS} seconds for
     * it to terminate. If the worker is still alive after the timeout, it is
     * interrupted forcibly.</p>
     */
    @Override
    public void close() {
        queue.clear();
        if (!queue.offer(POISON_PILL)) {
            // Should never happen after clear() — force-interrupt as last resort.
            worker.interrupt();
        }
        try {
            worker.join((long) DRAIN_TIMEOUT_SECONDS * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            worker.interrupt();
        }
    }

    // ── Worker ────────────────────────────────────────────────────────────────

    /**
     * Background loop — consumes CLEF events from the queue and POSTs them to Seq.
     *
     * <p>Exits cleanly on {@link #POISON_PILL} or on {@link InterruptedException}.
     * Network failures ({@link IOException}) are logged to {@code stderr} and the
     * loop continues — the failed event is lost but the writer remains operational.</p>
     */
    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String clef = queue.take();
                if (POISON_PILL.equals(clef)) break;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(seqUrl + "/api/events/raw"))
                        .header("Content-Type", "application/vnd.serilog.clef")
                        .header("X-Seq-ApiKey", apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(clef))
                        .build();

                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() / 100 != 2) {
                    System.err.println("[SeqHttpLogWriter] POST failed — HTTP "
                            + response.statusCode());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                System.err.println("[SeqHttpLogWriter] POST failed — " + e.getMessage());
            }
        }
    }
}