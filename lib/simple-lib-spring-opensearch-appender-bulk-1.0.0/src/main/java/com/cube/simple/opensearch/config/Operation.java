package com.cube.simple.opensearch.config;

import java.util.Optional;

/**
 * OpenSearch Bulk API operations supported by this appender (index and create only).
 *
 * @see <a href="https://docs.opensearch.org/latest/api-reference/document-apis/bulk/">Bulk API actions</a>
 */
public enum Operation {
    index,
    create;

    public static Optional<Operation> of(String value) {
        try {
            return Optional.of(valueOf(value));
        } catch (IllegalArgumentException ignored) {
        }
        return Optional.empty();
    }
}
