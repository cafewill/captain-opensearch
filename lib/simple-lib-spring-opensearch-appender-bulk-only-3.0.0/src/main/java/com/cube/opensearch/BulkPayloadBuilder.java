package com.cube.opensearch;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.Context;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.event.KeyValuePair;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
            "@timestamp", "level", "thread", "logger", "message"
    );

    private static final Class<?> OBJECT_APPENDING_MARKER_CLASS;
    private static final Method GET_FIELD_NAME_METHOD;
    private static final Field OBJECT_FIELD;

    static {
        Class<?> cls = null;
        Method method = null;
        Field field = null;
        try {
            cls = Class.forName("net.logstash.logback.marker.ObjectAppendingMarker");
            method = cls.getMethod("getFieldName");
            field = cls.getDeclaredField("object");
            field.setAccessible(true);
        } catch (Exception ignored) {
        }
        OBJECT_APPENDING_MARKER_CLASS = cls;
        GET_FIELD_NAME_METHOD = method;
        OBJECT_FIELD = field;
    }

    private final Context context;
    private final ObjectMapper objectMapper;
    private final String indexPattern;
    private final String type;
    private final String timestampFormat;
    private final String operation;
    private final boolean includeMdc;
    private final boolean includeKvp;
    private final boolean includeCallerData;
    private final boolean rawJsonMessage;
    private final int maxMessageSize;
    private final List<PropertyEncoder> properties;
    private final String keyPrefix;
    private final boolean objectSerialization;
    private final boolean includeStructuredArgs;

    BulkPayloadBuilder(Context context,
                       ObjectMapper objectMapper,
                       String indexPattern,
                       String type,
                       String timestampFormat,
                       String operation,
                       boolean includeMdc,
                       boolean includeKvp,
                       boolean includeCallerData,
                       boolean rawJsonMessage,
                       int maxMessageSize,
                       OpenSearchProperties properties,
                       String keyPrefix,
                       boolean objectSerialization,
                       boolean includeStructuredArgs) {
        this.context = context;
        this.objectMapper = objectMapper;
        this.indexPattern = indexPattern;
        this.type = type;
        this.timestampFormat = timestampFormat;
        this.operation = normalizeOperation(operation);
        this.includeMdc = includeMdc;
        this.includeKvp = includeKvp;
        this.includeCallerData = includeCallerData;
        this.rawJsonMessage = rawJsonMessage;
        this.maxMessageSize = maxMessageSize;
        this.properties = createPropertyEncoders(properties);
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
        this.objectSerialization = objectSerialization;
        this.includeStructuredArgs = includeStructuredArgs;
    }

    private String normalizeOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            return "index";
        }
        String lower = operation.trim().toLowerCase();
        return lower.equals("index") || lower.equals("create") ? lower : "index";
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
        putTimestamp(source, event);
        putIfNotBlank(source, "level", event.getLevel() == null ? null : event.getLevel().toString());
        putIfNotBlank(source, "thread", event.getThreadName());
        putIfNotBlank(source, "logger", event.getLoggerName());
        putMessage(source, event.getFormattedMessage());

        if (event.getThrowableProxy() != null) {
            putIfNotBlank(source, "exception_class", event.getThrowableProxy().getClassName());
            putIfNotBlank(source, "exception_message", event.getThrowableProxy().getMessage());
            putIfNotBlank(source, "stack_trace", stackTrace(event.getThrowableProxy()));
        }
        if (includeCallerData) {
            appendCallerData(source, event);
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
        if (includeKvp && event.getKeyValuePairs() != null) {
            for (KeyValuePair pair : event.getKeyValuePairs()) {
                if (pair == null || pair.key == null || pair.key.isBlank() || FIXED_DOC_FIELDS.contains(pair.key)) {
                    continue;
                }
                source.putPOJO(pair.key, pair.value);
            }
        }
        if (includeStructuredArgs) {
            processStructuredArgs(source, event);
        }
        for (PropertyEncoder property : properties) {
            property.write(source, event);
        }

        ObjectNode actionMeta = objectMapper.createObjectNode();
        if (type != null && !type.isBlank()) {
            actionMeta.put("_index", index).put("_type", type);
        } else {
            actionMeta.put("_index", index);
        }
        ObjectNode actionNode = objectMapper.createObjectNode();
        actionNode.set(operation, actionMeta);
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

    private void processStructuredArgs(ObjectNode source, ILoggingEvent event) {
        if (OBJECT_APPENDING_MARKER_CLASS == null || GET_FIELD_NAME_METHOD == null || OBJECT_FIELD == null) {
            return;
        }
        Object[] args = event.getArgumentArray();
        if (args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg == null || !OBJECT_APPENDING_MARKER_CLASS.isInstance(arg)) {
                continue;
            }
            try {
                String fieldName = (String) GET_FIELD_NAME_METHOD.invoke(arg);
                Object value = OBJECT_FIELD.get(arg);
                if (fieldName == null || fieldName.isBlank()) {
                    continue;
                }
                String prefixedKey = keyPrefix.isBlank() ? fieldName : keyPrefix + fieldName;
                if (value == null) {
                    source.putNull(prefixedKey);
                } else if (objectSerialization) {
                    source.set(prefixedKey, objectMapper.valueToTree(value));
                } else {
                    source.putPOJO(prefixedKey, value);
                }
            } catch (Exception ignored) {
            }
        }
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

    private void putTimestamp(ObjectNode source, ILoggingEvent event) {
        Instant instant = Instant.ofEpochMilli(event.getTimeStamp());
        if ("long".equalsIgnoreCase(timestampFormat)) {
            source.put("@timestamp", event.getTimeStamp());
            return;
        }
        if (timestampFormat != null && !timestampFormat.isBlank()) {
            String formatted = DateTimeFormatter.ofPattern(timestampFormat)
                    .withZone(ZoneId.systemDefault())
                    .format(instant);
            source.put("@timestamp", formatted);
            return;
        }
        source.put("@timestamp", DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .withZone(ZoneId.systemDefault())
                .format(instant));
    }

    private void putMessage(ObjectNode source, String message) throws JsonProcessingException {
        String effective = truncateMessage(message);
        if (!rawJsonMessage) {
            putIfNotBlank(source, "message", effective);
            return;
        }
        if (effective == null || effective.isBlank()) {
            return;
        }
        try {
            source.set("message", objectMapper.readTree(effective));
        } catch (Exception ignored) {
            source.put("message", effective);
        }
    }

    private String truncateMessage(String message) {
        if (message == null || maxMessageSize <= 0 || message.length() <= maxMessageSize) {
            return message;
        }
        return message.substring(0, maxMessageSize) + "..";
    }

    private void appendCallerData(ObjectNode source, ILoggingEvent event) {
        StackTraceElement[] callerData = event.getCallerData();
        if (callerData == null || callerData.length == 0 || callerData[0] == null) {
            return;
        }
        StackTraceElement caller = callerData[0];
        putIfNotBlank(source, "caller_class", caller.getClassName());
        putIfNotBlank(source, "caller_method", caller.getMethodName());
        putIfNotBlank(source, "caller_file", caller.getFileName());
        source.put("caller_line", caller.getLineNumber());
    }

    private String resolveIndex(ILoggingEvent event) {
        String date = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(event.getTimeStamp()));
        if (indexPattern == null || indexPattern.isBlank()) {
            return "app-logs-" + date;
        }
        return indexPattern
                .replace("%date{yyyy.MM.dd}", date)
                .replace("{date}", date);
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

    private List<PropertyEncoder> createPropertyEncoders(OpenSearchProperties properties) {
        if (properties == null || properties.getProperties().isEmpty()) {
            return List.of();
        }
        List<PropertyEncoder> encoders = new ArrayList<>();
        for (OpenSearchProperty property : properties.getProperties()) {
            if (property == null || property.getName() == null || property.getName().isBlank()) {
                continue;
            }
            PatternLayout layout = new PatternLayout();
            layout.setContext(context);
            layout.setPattern(property.getValue() == null ? "" : property.getValue());
            layout.start();
            encoders.add(new PropertyEncoder(property, layout));
        }
        return encoders;
    }

    private static final class PropertyEncoder {
        private final OpenSearchProperty property;
        private final PatternLayout layout;

        private PropertyEncoder(OpenSearchProperty property, PatternLayout layout) {
            this.property = property;
            this.layout = layout;
        }

        private void write(ObjectNode node, ILoggingEvent event) {
            String encoded = layout.doLayout(event);
            if (encoded == null) {
                return;
            }
            if (!property.isAllowEmpty() && encoded.isBlank()) {
                return;
            }
            String value = encoded.endsWith(System.lineSeparator())
                    ? encoded.substring(0, encoded.length() - System.lineSeparator().length())
                    : encoded;
            switch (property.getType()) {
                case INT -> {
                    try {
                        node.put(property.getName(), Long.parseLong(value.trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
                case FLOAT -> {
                    try {
                        node.put(property.getName(), Double.parseDouble(value.trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
                case BOOLEAN -> node.put(property.getName(), Boolean.parseBoolean(value.trim()));
                case STRING -> node.put(property.getName(), value);
            }
        }
    }

    record BulkItem(String index, String actionLine, String documentLine, ILoggingEvent event) {
    }
}
