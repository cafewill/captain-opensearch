package com.cube.simple.elasticsearch.config;

import java.util.Optional;

/**
 * Elasticsearch Bulk API operations supported by this appender.
 *
 * @see <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-bulk.html">Bulk API actions</a>
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
