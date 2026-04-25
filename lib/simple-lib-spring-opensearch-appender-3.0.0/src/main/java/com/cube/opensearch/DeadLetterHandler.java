package com.cube.opensearch;

import java.util.List;

interface DeadLetterHandler {
    void store(List<BulkPayloadBuilder.BulkItem> items, String message, Throwable cause);
}
