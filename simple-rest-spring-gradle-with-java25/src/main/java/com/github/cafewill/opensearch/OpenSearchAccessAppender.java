package com.github.cafewill.opensearch;

import java.io.IOException;

import com.github.cafewill.opensearch.config.Settings;

import ch.qos.logback.access.spi.IAccessEvent;

public class OpenSearchAccessAppender extends AbstractOpenSearchAppender<IAccessEvent> {

    public OpenSearchAccessAppender() {
    }

    public OpenSearchAccessAppender(Settings settings) {
        super(settings);
    }

    @Override
    protected void appendInternal(IAccessEvent eventObject) {
        eventObject.prepareForDeferredProcessing();
        publishEvent(eventObject);
    }

    protected AccessOpenSearchPublisher buildOpenSearchPublisher() throws IOException {
        return new AccessOpenSearchPublisher(getContext(), errorReporter, settings, opensearchProperties, headers);
    }


}
