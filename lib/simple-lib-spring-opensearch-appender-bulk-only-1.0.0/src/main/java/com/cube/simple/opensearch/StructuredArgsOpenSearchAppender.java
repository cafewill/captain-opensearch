package com.cube.simple.opensearch;

import java.io.IOException;

import com.cube.simple.opensearch.config.Settings;

public class StructuredArgsOpenSearchAppender extends OpenSearchAppender {

    public StructuredArgsOpenSearchAppender() {
    }

    public StructuredArgsOpenSearchAppender(Settings settings) {
        super(settings);
    }

    protected StructuredArgsOpenSearchPublisher buildOpenSearchPublisher() throws IOException {
        return new StructuredArgsOpenSearchPublisher(this.getContext(), this.errorReporter, this.settings,
                this.opensearchProperties, this.headers);
    }
}
