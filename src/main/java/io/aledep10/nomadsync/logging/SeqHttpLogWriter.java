package io.aledep10.nomadsync.logging;

import io.aledep10.nomadsync.config.NomadProperties;
import io.aledep10.nomadsync.util.StringUtil;

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
 * <h2>Configuration</h2>
 * <p>Requires {@link NomadProperties.Log#SEQ_URL} to be set in
 * {@code config.properties}. {@link NomadProperties.Log#SEQ_API_KEY} is
 * optional — defaults to an empty string (authentication disabled).
 * Both values are resolved by
 * {@link io.aledep10.nomadsync.service.LogService} before constructing
 * this writer.</p>
 *
 * <h2>Threading model</h2>
 * <p>The caller ({@link io.aledep10.nomadsync.service.LogService}) never blocks —
 * events are offered to an internal {@link BlockingQueue} and return immediately.
 * A dedicated daemon thread ({@code seq-log-writer}) consumes from the queue and
 * performs the HTTP POST in the background.</p>
 *
 * <p>The worker is a <em>daemon</em> thread: if the JVM exits without
 * {@link #close()} being called, the daemon terminates automatically rather than
 * keeping the process alive.</p>
 *
 * <h2>Backpressure</h2>
 * <p>Queue capacity is capped at {@value MAX_QUEUE_SIZE} events. If full
 * (e.g. Seq is unreachable for an extended period), incoming events are silently
 * dropped and a warning is written to {@code stderr}. This prevents the logging
 * subsystem from blocking application threads under load.</p>
 *
 * <h2>Shutdown</h2>
 * <p>{@link #close()} clears the queue, inserts a {@link #POISON_PILL} sentinel,
 * and waits up to {@value DRAIN_TIMEOUT_SECONDS} seconds for the worker to finish
 * any in-flight request. If the worker is still alive after the timeout, it is
 * interrupted.</p>
 *
 * <h2>HTTP failure handling</h2>
 * <p>When Seq is unreachable, the first failure is reported to {@code stderr};
 * subsequent failures are suppressed until connectivity is restored. When the
 * server becomes reachable again, a single recovery notice is written to
 * {@code stderr}. This circuit-breaker pattern prevents log flooding when Seq
 * is intentionally offline (e.g. Docker Desktop not running).</p>
 *
 * <h2>URL normalisation</h2>
 * <p>The constructor accepts the base URL (e.g. {@code http://localhost:5341}),
 * with or without a trailing slash. The ingestion path
 * {@code /api/events/raw} is appended internally — callers must not include it
 * in the value of {@link NomadProperties.Log#SEQ_URL}.</p>
 */
public class SeqHttpLogWriter implements LogWriter {

    private static final String POISON_PILL          = "__SHUTDOWN__";
    private static final int    MAX_QUEUE_SIZE        = 1000;
    private static final int    DRAIN_TIMEOUT_SECONDS = 5;

    private final BlockingQueue<String> queue     = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private final Thread                worker;
    private final HttpClient            client    = HttpClient.newHttpClient();
    private final ClefFormatter         formatter = new ClefFormatter();
    private final String                ingestUrl;
    private final String                apiKey;

    /**
     * {@code true} while Seq is unreachable — suppresses repeated error output
     * after the first failure until connectivity is restored.
     */
    private boolean seqDown = false;

    /**
     * Constructs the writer and starts the background daemon thread.
     *
     * <p>The ingestion endpoint is built from {@code seqUrl} by appending
     * {@code /api/events/raw}, normalising any double slash. Pass the base URL
     * (e.g. {@code http://localhost:5341}) — the path is handled internally.</p>
     *
     * @param seqUrl base URL of the Seq server, from
     *               {@link NomadProperties.Log#SEQ_URL}
     * @param apiKey Seq API key from {@link NomadProperties.Log#SEQ_API_KEY},
     *               or an empty string if authentication is disabled
     */
    public SeqHttpLogWriter(String seqUrl, String apiKey) {
        this.ingestUrl = normaliseUrl(seqUrl);
        this.apiKey    = apiKey;
        worker = new Thread(this::workerLoop, "seq-log-writer");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Enqueues a CLEF-formatted log event for asynchronous delivery to Seq.
     *
     * <p>Never blocks. If the internal queue is full, the event is dropped and
     * a warning is written to {@code stderr}.</p>
     *
     * @param level       severity level of the event
     * @param universalId repoSlug of the originating vault, or {@code "SYSTEM"}
     * @param message     the log message
     * @param cause       exception to include as {@code @x}, or {@code null}
     */
    @Override
    public void write(LogLevel level, String universalId, String message, Throwable cause) {
        String clef = formatter.format(level, universalId, message, cause).getFirst();
        if (!queue.offer(clef)) {
            System.err.println("[SeqHttpLogWriter] queue full - event dropped: " + message);
        }
    }

    /**
     * Initiates an orderly shutdown of the background writer thread.
     *
     * <p>Clears any queued events, inserts a {@link #POISON_PILL} sentinel to
     * signal the worker to stop, and waits up to {@value DRAIN_TIMEOUT_SECONDS}
     * seconds for it to terminate. If the worker is still alive after the timeout,
     * it is interrupted.</p>
     */
    @Override
    public void close() {
        // Do NOT clear — let the worker drain all pending events first
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
     * <p>Exits cleanly on {@link #POISON_PILL} or {@link InterruptedException}.</p>
     *
     * <p>On the first {@link IOException} or non-2xx response, a single notice is
     * written to {@code stderr} and {@link #seqDown} is set to {@code true} —
     * subsequent failures are suppressed until the server responds successfully
     * again. On recovery, a single notice is written to {@code stderr} and normal
     * operation resumes.</p>
     */
    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String clef = queue.take();
                if (POISON_PILL.equals(clef)) break;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(ingestUrl))
                        .header("Content-Type", "application/vnd.serilog.clef")
                        .header("X-Seq-ApiKey", apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(clef))
                        .build();

                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() / 100 != 2) {
                    if (!seqDown) {
                        System.err.println("[SeqHttpLogWriter] Seq unreachable - HTTP "
                                + response.statusCode() + ". Further errors suppressed.");
                        seqDown = true;
                    }
                } else if (seqDown) {
                    seqDown = false;
                    System.err.println("[SeqHttpLogWriter] Seq connection restored.");
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                if (!seqDown) {
                    String cause = e.getMessage();
                    System.err.println("[SeqHttpLogWriter] Seq unreachable"
                            + (cause != null ? " - " + cause : "")
                            + ". Further errors suppressed.");
                    seqDown = true;
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds the full Seq ingestion URL from the base URL.
     *
     * <p>Strips any trailing slash from {@code seqUrl} before appending
     * {@code /api/events/raw}, preventing double-slash issues regardless of
     * how the property value is formatted.</p>
     *
     * @param seqUrl base URL, e.g. {@code http://localhost:5341} or
     *               {@code http://localhost:5341/}
     * @return normalised ingestion URL
     */
    private static String normaliseUrl(String seqUrl) {
        String base = seqUrl.endsWith("/")
                ? seqUrl.substring(0, seqUrl.length() - 1)
                : seqUrl;
        return base + "/api/events/raw";
    }
}