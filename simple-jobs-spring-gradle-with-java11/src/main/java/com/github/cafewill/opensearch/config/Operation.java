package com.github.cafewill.opensearch.config;

import java.util.Optional;

/**
 * OpenSearch Bulk API operations supported by this appender.
 *
 * @see <a href="https://docs.opensearch.org/latest/api-reference/document-apis/bulk/">Bulk API actions</a>
 */
public enum Operation {
    index,
    create,
    update,
    delete;

    public static Optional<Operation> of( String value ) {
        try {
            return Optional.of( valueOf( value ) );
        } catch ( IllegalArgumentException ignored ) {
        }

        return Optional.empty( );
    }
}
