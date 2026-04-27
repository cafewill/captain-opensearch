package com.cube.simple.opensearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OpenSearchHeaders {
    private final List<OpenSearchHeader> headers = new ArrayList<>();

    public List<OpenSearchHeader> getHeaders() {
        return Collections.unmodifiableList(headers);
    }

    public void addHeader(OpenSearchHeader header) {
        if (header != null) {
            headers.add(header);
        }
    }
}
