package com.cube.simple.opensearch;

import java.util.Collections;
import java.util.List;

final class SendResult {
    private final boolean success;
    private final List<BulkPayloadBuilder.BulkItem> retryableItems;
    private final String message;
    private final Throwable cause;

    private SendResult(boolean success,
                       List<BulkPayloadBuilder.BulkItem> retryableItems,
                       String message,
                       Throwable cause) {
        this.success = success;
        this.retryableItems = retryableItems == null ? Collections.emptyList() : retryableItems;
        this.message = message;
        this.cause = cause;
    }

    static SendResult success() {
        return new SendResult(true, Collections.emptyList(), "success", null);
    }

    static SendResult retryable(List<BulkPayloadBuilder.BulkItem> retryableItems, String message, Throwable cause) {
        return new SendResult(false, retryableItems, message, cause);
    }

    static SendResult fatal(String message, Throwable cause) {
        return new SendResult(false, Collections.emptyList(), message, cause);
    }

    boolean isSuccess() {
        return success;
    }

    List<BulkPayloadBuilder.BulkItem> retryableItems() {
        return retryableItems;
    }

    String message() {
        return message;
    }

    Throwable cause() {
        return cause;
    }
}
