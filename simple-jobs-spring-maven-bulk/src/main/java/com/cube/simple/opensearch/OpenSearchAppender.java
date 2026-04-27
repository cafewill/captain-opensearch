package com.cube.simple.opensearch;

public class OpenSearchAppender extends AbstractOpenSearchAppender {
    @Override
    protected String getThreadName() {
        return "cube-opensearch-writer";
    }
}
