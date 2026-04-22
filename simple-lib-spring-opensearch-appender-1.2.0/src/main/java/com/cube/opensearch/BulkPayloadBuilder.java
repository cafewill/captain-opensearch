package com.cube.opensearch;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BulkPayloadBuilder {
    private static final Set<String> NUMERIC_MDC_FIELDS = Set.of(
            "http_status", "duration_ms", "elapsed_ms", "retry_count", "status"
    );
    private static final Set<String> BOOLEAN_MDC_FIELDS = Set.of(
            "success", "retryable"
    );
    private static final Set<String> FIXED_DOC_FIELDS = Set.of(
            "@timestamp", "app", "env", "instance_id", "host", "level", "thread", "logger", "message"
    );

    private final ObjectMapper objectMapper;
    private final String indexPattern;
    private final String app;
    private final String env;
    private final String instanceId;
    private final String timestampZone;
    private final boolean includeMdc;

    BulkPayloadBuilder(ObjectMapper objectMapper,
                       String indexPattern,
                       String app,
                       String env,
                       String instanceId,
                       String timestampZone,
                       boolean includeMdc) {
        this.objectMapper = objectMapper;
        this.indexPattern = indexPattern;
        this.app = app;
        this.env = env;
        this.instanceId = instanceId;
        this.timestampZone = timestampZone == null || timestampZone.isBlank() ? "UTC" : timestampZone;
        this.includeMdc = includeMdc;
    }

    List<BulkItem> buildItems(List<ILoggingEvent> events) throws JsonProcessingException {
        List<BulkItem> items = new ArrayList<>(events.size());
        for (ILoggingEvent event : events) {
            items.add(buildItem(event));
        }
        return items;
    }

    BulkItem buildItem(ILoggingEvent event) throws JsonProcessingException {
        ObjectNode source = objectMapper.createObjectNode();
        String index = resolveIndex(event);

        String formattedTimestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .withZone(resolveZone())
                .format(Instant.ofEpochMilli(event.getTimeStamp()));

        source.put("@timestamp", formattedTimestamp);
        putIfNotBlank(source, "app", app);
        putIfNotBlank(source, "env", env);
        putIfNotBlank(source, "instance_id", instanceId);
        putIfNotBlank(source, "host", instanceId);
        putIfNotBlank(source, "level", event.getLevel() == null ? null : event.getLevel().toString());
        putIfNotBlank(source, "thread", event.getThreadName());
        putIfNotBlank(source, "logger", event.getLoggerName());
        putIfNotBlank(source, "message", event.getFormattedMessage());

        if (event.getThrowableProxy() != null) {
            putIfNotBlank(source, "exception_class", event.getThrowableProxy().getClassName());
            putIfNotBlank(source, "exception_message", event.getThrowableProxy().getMessage());
            putIfNotBlank(source, "stack_trace", stackTrace(event.getThrowableProxy()));
        }

        if (includeMdc) {
            Map<String, String> mdcMap = event.getMDCPropertyMap();
            if (mdcMap != null && !mdcMap.isEmpty()) {
                for (Map.Entry<String, String> entry : mdcMap.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isBlank()) {
                        continue;
                    }
                    if (FIXED_DOC_FIELDS.contains(entry.getKey())) {
                        continue;
                    }
                    Object coerced = coerceMdcValue(entry.getKey(), entry.getValue());
                    if (coerced instanceof Number number) {
                        source.putPOJO(entry.getKey(), number);
                    } else if (coerced instanceof Boolean bool) {
                        source.put(entry.getKey(), bool);
                    } else {
                        source.put(entry.getKey(), String.valueOf(coerced));
                    }
                }
            }
        }

        ObjectNode actionNode = objectMapper.createObjectNode();
        actionNode.putObject("index").put("_index", index);
        String action = objectMapper.writeValueAsString(actionNode);
        String document = objectMapper.writeValueAsString(source);
        return new BulkItem(index, action, document, event);
    }

    String buildPayload(List<BulkItem> items) {
        StringBuilder payload = new StringBuilder(items.size() * 512);
        for (BulkItem item : items) {
            payload.append(item.actionLine()).append('\n');
            payload.append(item.documentLine()).append('\n');
        }
        return payload.toString();
    }

    private Object coerceMdcValue(String key, String value) {
        if (NUMERIC_MDC_FIELDS.contains(key)) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
            }
        }
        if (BOOLEAN_MDC_FIELDS.contains(key)) {
            return Boolean.parseBoolean(value);
        }
        return value;
    }

    private String resolveIndex(ILoggingEvent event) {
        String date = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(resolveZone())
                .format(Instant.ofEpochMilli(event.getTimeStamp()));
        if (indexPattern == null || indexPattern.isBlank()) {
            return "app-logs-" + date;
        }
        return indexPattern
                .replace("%date{yyyy.MM.dd}", date)
                .replace("{date}", date);
    }

    private ZoneId resolveZone() {
        try {
            return ZoneId.of(timestampZone);
        } catch (Exception ignored) {
            return ZoneId.of("UTC");
        }
    }

    private String stackTrace(IThrowableProxy throwableProxy) {
        StringBuilder sb = new StringBuilder();
        appendThrowable(sb, throwableProxy, new HashSet<>());
        return sb.toString();
    }

    private void appendThrowable(StringBuilder sb, IThrowableProxy throwable, Set<IThrowableProxy> visited) {
        if (throwable == null || !visited.add(throwable)) {
            return;
        }
        sb.append(throwable.getClassName());
        if (throwable.getMessage() != null) {
            sb.append(": ").append(throwable.getMessage());
        }
        sb.append('\n');
        StackTraceElementProxy[] stackTrace = throwable.getStackTraceElementProxyArray();
        if (stackTrace != null) {
            for (StackTraceElementProxy element : stackTrace) {
                sb.append("\tat ").append(element.getStackTraceElement()).append('\n');
            }
        }
        if (throwable.getCause() != null) {
            sb.append("Caused by: ");
            appendThrowable(sb, throwable.getCause(), visited);
        }
    }

    private void putIfNotBlank(ObjectNode node, String key, String value) {
        if (value != null && !value.isBlank()) {
            node.put(key, value);
        }
    }

    record BulkItem(String index, String actionLine, String documentLine, ILoggingEvent event) {
    }
}
