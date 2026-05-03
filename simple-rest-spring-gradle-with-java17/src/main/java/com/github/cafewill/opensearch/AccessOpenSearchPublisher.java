package com.github.cafewill.opensearch;

import java.io.IOException;

import com.github.cafewill.opensearch.config.OpenSearchProperties;
import com.github.cafewill.opensearch.config.HttpRequestHeaders;
import com.github.cafewill.opensearch.config.Property;
import com.github.cafewill.opensearch.config.Settings;
import com.github.cafewill.opensearch.util.AbstractPropertyAndEncoder;
import com.github.cafewill.opensearch.util.AccessPropertyAndEncoder;
import com.github.cafewill.opensearch.util.ErrorReporter;
import com.fasterxml.jackson.core.JsonGenerator;

import ch.qos.logback.access.spi.IAccessEvent;
import ch.qos.logback.core.Context;

public class AccessOpenSearchPublisher extends AbstractOpenSearchPublisher<IAccessEvent> {

    public AccessOpenSearchPublisher(Context context, ErrorReporter errorReporter, Settings settings, OpenSearchProperties properties, HttpRequestHeaders httpRequestHeaders) throws IOException {
        super(context, errorReporter, settings, properties, httpRequestHeaders);
    }

    @Override
    protected AbstractPropertyAndEncoder<IAccessEvent> buildPropertyAndEncoder(Context context, Property property) {
        return new AccessPropertyAndEncoder(property, context);
    }

    @Override
    protected void serializeCommonFields(JsonGenerator gen, IAccessEvent event) throws IOException {
        gen.writeObjectField("@timestamp", getTimestamp(event.getTimeStamp()));
    }
}
