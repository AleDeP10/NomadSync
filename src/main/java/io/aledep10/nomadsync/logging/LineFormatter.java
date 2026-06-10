package io.aledep10.nomadsync.logging;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LineFormatter implements LogFormatter {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public List<String> format(LogLevel level, String universalId,
                               String message, Throwable cause) {
        List<String> result = new ArrayList<>();
        String timestamp = LocalDateTime.now().format(FORMATTER);
        result.add("[%s] [%s] [%s] %s".formatted(timestamp, level, universalId, message));
        if (cause != null) {
        Arrays.stream(cause.getStackTrace())
                .map(StackTraceElement::toString)
                .forEach(result::add);
        }
        return result;
    }
}
