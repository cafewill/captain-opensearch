package com.cube.simple.opensearch;

public class StructuredArgsOpenSearchAppender extends OpenSearchAppender {

    public StructuredArgsOpenSearchAppender() {
        setIncludeStructuredArgs(true);
    }
}
