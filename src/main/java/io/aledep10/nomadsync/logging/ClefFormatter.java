package io.aledep10.nomadsync.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aledep10.nomadsync.util.DateFormats;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.util.List;

public class ClefFormatter implements LogFormatter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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