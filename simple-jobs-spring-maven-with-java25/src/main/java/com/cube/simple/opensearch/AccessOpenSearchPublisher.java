package com.cube.simple.opensearch;

import java.io.IOException;

import com.cube.simple.opensearch.config.OpenSearchProperties;
import com.cube.simple.opensearch.config.HttpRequestHeaders;
import com.cube.simple.opensearch.config.Property;
import com.cube.simple.opensearch.config.Settings;
import com.cube.simple.opensearch.util.AbstractPropertyAndEncoder;
import com.cube.simple.opensearch.util.AccessPropertyAndEncoder;
import com.cube.simple.opensearch.util.ErrorReporter;
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
