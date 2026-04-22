package com.cube.opensearch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

final class Slf4jDeadLetterHandler implements DeadLetterHandler {
    private final Logger logger;

    Slf4jDeadLetterHandler(String loggerName) {
        this.logger = LoggerFactory.getLogger(loggerName == null || loggerName.isBlank()
                ? "com.cube.opensearch.deadletter"
                : loggerName);
    }

    @Override
    public void store(List<BulkPayloadBuilder.BulkItem> items, String reason, Throwable cause) {
        for (BulkPayloadBuilder.BulkItem item : items) {
            if (cause == null) {
                logger.error("dead-letter reason={} payload={}", reason, item.documentLine());
            } else {
                logger.error("dead-letter reason={} payload={}", reason, item.documentLine(), cause);
            }
        }
    }
}
