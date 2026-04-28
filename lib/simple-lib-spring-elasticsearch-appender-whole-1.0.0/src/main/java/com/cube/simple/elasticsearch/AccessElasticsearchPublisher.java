package com.cube.simple.elasticsearch;

import java.io.IOException;

import com.cube.simple.elasticsearch.config.ElasticsearchProperties;
import com.cube.simple.elasticsearch.config.HttpRequestHeaders;
import com.cube.simple.elasticsearch.config.Property;
import com.cube.simple.elasticsearch.config.Settings;
import com.cube.simple.elasticsearch.util.AbstractPropertyAndEncoder;
import com.cube.simple.elasticsearch.util.AccessPropertyAndEncoder;
import com.cube.simple.elasticsearch.util.ErrorReporter;
import com.fasterxml.jackson.core.JsonGenerator;

import ch.qos.logback.access.spi.IAccessEvent;
import ch.qos.logback.core.Context;

public class AccessElasticsearchPublisher extends AbstractElasticsearchPublisher<IAccessEvent> {

    public AccessElasticsearchPublisher(Context context, ErrorReporter errorReporter, Settings settings, ElasticsearchProperties properties, HttpRequestHeaders httpRequestHeaders) throws IOException {
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
