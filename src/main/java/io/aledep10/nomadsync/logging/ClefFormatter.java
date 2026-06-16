package io.aledep10.nomadsync.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aledep10.nomadsync.util.DateFormats;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Formats log events as CLEF (Compact Log Event Format) JSON for Seq ingestion.
 *
 * <p>Each event produces a single-line JSON string conforming to the
 * <a href="https://clef-json.org">CLEF specification</a>, suitable for
 * HTTP POST to the Seq ingestion endpoint.</p>
 *
 * <h2>Field mapping</h2>
 * <table border="1">
 *   <caption>CLEF field mapping</caption>
 *   <tr><th>CLEF field</th><th>Source</th><th>Notes</th></tr>
 *   <tr><td>{@code @t}</td><td>{@link OffsetDateTime#now()}</td><td>ISO-8601 with offset</td></tr>
 *   <tr><td>{@code @l}</td><td>{@link LogLevel#toClef()}</td><td>Seq severity string</td></tr>
 *   <tr><td>{@code @m}</td><td>message</td><td>Human-readable log message</td></tr>
 *   <tr><td>{@code @x}</td><td>cause stack trace</td><td>Omitted if {@code cause} is {@code null}</td></tr>
 *   <tr><td>{@code vault}</td><td>universalId</td><td>repoSlug or {@code "SYSTEM"}</td></tr>
 * </table>
 *
 * <h2>Thread safety</h2>
 * <p>{@link ObjectMapper} is thread-safe after construction — the shared static
 * instance is safe for concurrent use by multiple log writer threads.</p>
 */
public class ClefFormatter implements LogFormatter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Formats a single log event as a CLEF JSON line.
     *
     * @param level       severity level
     * @param universalId repoSlug of the originating vault, or {@code "SYSTEM"}
     * @param message     human-readable log message
     * @param cause       exception to include as {@code @x}, or {@code null}
     * @return a single-element list containing the CLEF JSON string
     */
    @Override
    public List<String> format(LogLevel level, String universalId,
                               String message, Throwable cause) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("@t", OffsetDateTime.now().format(DateFormats.ISO_INSTANT));
        node.put("@l", level.toClef());
        node.put("@m", message);
        node.put("vault", universalId);
        if (cause != null) {
            StringWriter sw = new StringWriter();
            cause.printStackTrace(new PrintWriter(sw));
            node.put("@x", sw.toString());
        }
        return List.of(node.toString());
    }
}
