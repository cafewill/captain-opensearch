package com.cube.opensearch;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BulkPayloadBuilder {
    private static final DateTimeFormatter DATE_IDX = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter DATE_TS = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter DATE_TS_LOCAL = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TS_KST = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("Asia/Seoul"));
    private static final Set<String> NUMERIC_MDC_FIELDS = Set.of("http_status", "duration_ms");
    private static final Set<String> BOOLEAN_MDC_FIELDS = Set.of();
    private static final Set<String> FIXED_DOC_FIELDS = Set.of("app", "env", "instance_id");

    private final ObjectMapper objectMapper;
    private final String indexPattern;
    private final String app;
    private final String env;
    private final String instanceId;

    BulkPayloadBuilder(ObjectMapper objectMapper, String indexPattern, String app, String env, String instanceId) {
        this.objectMapper = objectMapper;
        this.indexPattern = indexPattern;
        this.app = app;
        this.env = env;
        this.instanceId = instanceId;
    }

    List<BulkItem> buildItems(List<ILoggingEvent> events) throws JsonProcessingException {
        List<BulkItem> items = new ArrayList<>(events.size());
        for (ILoggingEvent event : events) {
            items.add(buildItem(event));
        }
        return items;
    }

    BulkItem buildItem(ILoggingEvent event) throws JsonProcessingException {
        Instant ts = Instant.ofEpochMilli(event.getTimeStamp());
        String resolvedIndex = indexPattern.replace("%date{yyyy.MM.dd}", DATE_IDX.format(ts));

        Map<String, Object> action = Map.of("index", Map.of("_index", resolvedIndex));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("@timestamp", DATE_TS.format(ts));
        document.put("@timestamp_local", DATE_TS_LOCAL.format(ts));
        document.put("@timestamp_kst", DATE_TS_KST.format(ts));
        document.put("level", event.getLevel().toString());
        document.put("message", event.getFormattedMessage());
        document.put("logger_name", event.getLoggerName());
        document.put("thread_name", event.getThreadName());
        document.put("app", app == null ? "" : app);
        document.put("env", env == null ? "local" : env);
        document.put("instance_id", instanceId == null ? "" : instanceId);

        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null && !mdc.isEmpty()) {
            for (Map.Entry<String, String> entry : mdc.entrySet()) {
                String key = entry.getKey();
                if (FIXED_DOC_FIELDS.contains(key)) {
                    continue;
                }
                document.put(key, coerceMdcValue(key, entry.getValue()));
            }
        }

        if (event.getThrowableProxy() != null) {
            document.put("stack_trace", stackTrace(event.getThrowableProxy()));
        }

        String actionLine = objectMapper.writeValueAsString(action);
        String documentLine = objectMapper.writeValueAsString(document);
        return new BulkItem(event, actionLine, documentLine);
    }

    String buildPayload(List<BulkItem> items) {
        StringBuilder sb = new StringBuilder(items.size() * 256);
        for (BulkItem item : items) {
            sb.append(item.actionLine()).append('\n');
            sb.append(item.documentLine()).append('\n');
        }
        return sb.toString();
    }

    private Object coerceMdcValue(String key, String value) {
        if (value == null) {
            return null;
        }
        if (NUMERIC_MDC_FIELDS.contains(key)) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        if (BOOLEAN_MDC_FIELDS.contains(key)) {
            return Boolean.parseBoolean(value);
        }
        return value;
    }

    private String stackTrace(IThrowableProxy throwable) {
        StringBuilder sb = new StringBuilder();
        appendThrowable(sb, throwable);
        return sb.toString();
    }

    private void appendThrowable(StringBuilder sb, IThrowableProxy throwable) {
        sb.append(throwable.getClassName());
        if (throwable.getMessage() != null) {
            sb.append(": ").append(throwable.getMessage());
        }
        sb.append('\n');
        for (StackTraceElementProxy proxy : throwable.getStackTraceElementProxyArray()) {
            sb.append("\tat ").append(proxy.getSTEAsString()).append('\n');
        }
        if (throwable.getCause() != null) {
            sb.append("Caused by: ");
            appendThrowable(sb, throwable.getCause());
        }
    }

    record BulkItem(ILoggingEvent event, String actionLine, String documentLine) {
    }
}
