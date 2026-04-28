package com.cube.simple.opensearch;

import java.io.IOException;
import java.lang.reflect.Field;

import com.cube.simple.opensearch.config.OpenSearchProperties;
import com.cube.simple.opensearch.config.HttpRequestHeaders;
import com.cube.simple.opensearch.config.Settings;
import com.cube.simple.opensearch.util.ErrorReporter;
import com.fasterxml.jackson.core.JsonGenerator;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Context;
import net.logstash.logback.marker.ObjectAppendingMarker;

public class StructuredArgsOpenSearchPublisher extends ClassicOpenSearchPublisher {
    private String keyPrefix;
    private Field field;
    private ErrorReporter errorReporter;

    public StructuredArgsOpenSearchPublisher(Context context, ErrorReporter errorReporter, Settings settings, OpenSearchProperties properties,
                                                HttpRequestHeaders headers) throws IOException {
        super(context, errorReporter, settings, properties, headers);

        this.errorReporter = errorReporter;

        keyPrefix = "";
        if (settings != null && settings.getKeyPrefix() != null) {
            keyPrefix = settings.getKeyPrefix();
        }

        try {
            field = ObjectAppendingMarker.class.getDeclaredField("object");
            field.setAccessible(true);
        } catch (NoSuchFieldException e) {
            // message will be logged without object
            errorReporter.logError("error in logging with object serialization", e);
        }
    }

    protected void serializeCommonFields(JsonGenerator gen, ILoggingEvent event) throws IOException {
        super.serializeCommonFields(gen, event);

        if (event.getArgumentArray() != null) {
            Object[] eventArgs = event.getArgumentArray();
            for (Object eventArg : eventArgs) {
                if (eventArg instanceof ObjectAppendingMarker) {
                    ObjectAppendingMarker marker = (ObjectAppendingMarker) eventArg;
                    if (field != null && settings != null && settings.isObjectSerialization() &&
                            marker.getFieldValue().toString().contains("@")) {
                        try {
                            Object obj = field.get(marker);
                            if (obj != null) {
                                gen.writeObjectField(keyPrefix + marker.getFieldName(), obj);
                            }
                        } catch (IllegalAccessException e) {
                            // message will be logged without object
                            errorReporter.logError("error in logging with object serialization", e);
                        }
                    } else
                        gen.writeObjectField(keyPrefix + marker.getFieldName(), marker.getFieldValue());
                }
            }
        }
    }

}
