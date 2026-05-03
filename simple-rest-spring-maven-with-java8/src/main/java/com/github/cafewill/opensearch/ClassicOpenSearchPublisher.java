package com.github.cafewill.opensearch;

import java.io.IOException;
import java.util.Map;

import com.github.cafewill.opensearch.config.OpenSearchProperties;
import com.github.cafewill.opensearch.config.HttpRequestHeaders;
import com.github.cafewill.opensearch.config.Property;
import com.github.cafewill.opensearch.config.Settings;
import com.github.cafewill.opensearch.util.AbstractPropertyAndEncoder;
import com.github.cafewill.opensearch.util.ClassicPropertyAndEncoder;
import com.github.cafewill.opensearch.util.ErrorReporter;
import com.fasterxml.jackson.core.JsonGenerator;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Context;

public class ClassicOpenSearchPublisher extends AbstractOpenSearchPublisher<ILoggingEvent> {

    public ClassicOpenSearchPublisher(Context context, ErrorReporter errorReporter, Settings settings, OpenSearchProperties properties, HttpRequestHeaders headers) throws IOException {
        super(context, errorReporter, settings, properties, headers);
    }

    @Override
    protected AbstractPropertyAndEncoder<ILoggingEvent> buildPropertyAndEncoder(Context context, Property property) {
        return new ClassicPropertyAndEncoder(property, context);
    }

    @Override
    protected void serializeCommonFields(JsonGenerator gen, ILoggingEvent event) throws IOException {
        gen.writeObjectField("@timestamp", getTimestamp(event.getTimeStamp()));

        if (settings.isRawJsonMessage()) {
            gen.writeFieldName("message");
            gen.writeRawValue(event.getFormattedMessage());
        } else {
            String formattedMessage = event.getFormattedMessage();
            if (settings.getMaxMessageSize() > 0 && formattedMessage != null && formattedMessage.length() > settings.getMaxMessageSize()) {
                formattedMessage = formattedMessage.substring(0, settings.getMaxMessageSize()) + "..";
            }
            gen.writeObjectField("message", formattedMessage);
        }

        if (settings.isIncludeMdc()) {
            for (Map.Entry<String, String> entry : event.getMDCPropertyMap().entrySet()) {
                gen.writeObjectField(entry.getKey(), entry.getValue());
            }
        }
    }
}
