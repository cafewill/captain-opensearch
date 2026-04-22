package com.cube.opensearch;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractOpenSearchAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RetryPolicy retryPolicy = new RetryPolicy();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private String url;
    private String index;
    private String username;
    private String password;
    private String app;
    private String env;
    private int maxBatchBytes = 256 * 1024;
    private int maxBatchSize = 200;
    private int maxMessageSize = 32 * 1024;
    private int flushIntervalSeconds = 3;
    private int queueSize = 10_000;
    private int connectTimeoutMillis = 3_000;
    private int readTimeoutMillis = 5_000;
    private long retryInitialDelayMillis = 500L;
    private long retryMaxDelayMillis = 10_000L;
    private int maxRetries = 3;
    private boolean retryPartialFailures = true;
    private boolean trustAllSsl = true; // 내부망 자가 서명 인증서 전용 기본값 유지
    private String deadLetterLoggerName = "com.cube.opensearch.deadletter";
    private String instanceId;
    private String timestampZone = "UTC";
    private String timestampFormat;
    private String operation = "index";
    private boolean includeMdc = true;
    private boolean includeKvp = false;
    private boolean includeCallerData = false;
    private boolean rawJsonMessage = false;
    private boolean logsToStderr = false;
    private boolean errorsToStderr = false;
    private String loggerName;
    private String errorLoggerName;
    private OpenSearchHeaders headers;
    private OpenSearchProperties properties;

    private BlockingQueue<ILoggingEvent> queue;
    private Thread writerThread;
    private BulkPayloadBuilder payloadBuilder;
    private OpenSearchSender sender;
    private DeadLetterHandler deadLetterHandler;

    public AbstractOpenSearchAppender() {
    }

    @Override
    public void start() {
        if (!validateConfiguration()) {
            return;
        }

        retryPolicy.setMaxRetries(maxRetries);
        retryPolicy.setInitialDelayMillis(retryInitialDelayMillis);
        retryPolicy.setMaxDelayMillis(retryMaxDelayMillis);
        retryPolicy.setRetryPartialFailures(retryPartialFailures);

        String resolvedInstanceId = firstNonBlank(instanceId, OpenSearchSender.resolveInstanceId());
        this.queue = new ArrayBlockingQueue<>(Math.max(100, queueSize));
        this.payloadBuilder = new BulkPayloadBuilder(
                getContext(),
                objectMapper,
                index,
                firstNonBlank(app, "unknown-app"),
                firstNonBlank(env, "default"),
                resolvedInstanceId,
                timestampZone,
                timestampFormat,
                operation,
                includeMdc,
                includeKvp,
                includeCallerData,
                rawJsonMessage,
                maxMessageSize,
                properties
        );
        this.sender = new OpenSearchSender(
                objectMapper,
                url,
                username,
                password,
                connectTimeoutMillis,
                readTimeoutMillis,
                trustAllSsl,
                headers
        );
        this.deadLetterHandler = new Slf4jDeadLetterHandler(deadLetterLoggerName);
        running.set(true);
        writerThread = new Thread(this::runLoop, getThreadName());
        writerThread.setDaemon(true);
        writerThread.start();
        super.start();
    }

    @Override
    public void stop() {
        if (!isStarted()) {
            return;
        }
        running.set(false);
        if (writerThread != null) {
            writerThread.interrupt();
            try {
                writerThread.join(3_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flushRemaining();
        super.stop();
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        if (!isStarted() || eventObject == null) {
            return;
        }
        eventObject.prepareForDeferredProcessing();
        if (includeCallerData) {
            eventObject.getCallerData();
        }
        if (!queue.offer(eventObject)) {
            addWarn("OpenSearch queue is full. dropping log event.");
        }
    }

    protected abstract String getThreadName();

    private boolean validateConfiguration() {
        boolean valid = true;
        if (url == null || url.isBlank()) {
            addError("url must not be blank");
            valid = false;
        }
        if (index == null || index.isBlank()) {
            addError("index must not be blank");
            valid = false;
        }
        if (flushIntervalSeconds <= 0) {
            addWarn("flushIntervalSeconds <= 0. using default 3");
            flushIntervalSeconds = 3;
        }
        if (maxBatchBytes <= 0) {
            addWarn("maxBatchBytes <= 0. using default 262144");
            maxBatchBytes = 256 * 1024;
        }
        if (maxBatchSize < 0) {
            addWarn("maxBatchSize < 0. using default 200");
            maxBatchSize = 200;
        }
        if (maxMessageSize < 0) {
            addWarn("maxMessageSize < 0. using default 32768");
            maxMessageSize = 32 * 1024;
        }
        if (!"index".equalsIgnoreCase(operation) && !"create".equalsIgnoreCase(operation)) {
            addWarn("operation must be index or create. using default index");
            operation = "index";
        }
        return valid;
    }

    private void runLoop() {
        List<ILoggingEvent> batch = new ArrayList<>();
        long lastFlushAt = System.currentTimeMillis();

        while (running.get() || !queue.isEmpty()) {
            try {
                ILoggingEvent event = queue.poll(500, TimeUnit.MILLISECONDS);
                if (event != null) {
                    batch.add(event);
                }

                boolean timeToFlush = (System.currentTimeMillis() - lastFlushAt) >= (flushIntervalSeconds * 1000L);
                boolean sizeToFlush = estimateBytes(batch) >= maxBatchBytes;
                boolean countToFlush = maxBatchSize > 0 && batch.size() >= maxBatchSize;
                if (!batch.isEmpty() && (timeToFlush || sizeToFlush || countToFlush)) {
                    sendWithRetry(new ArrayList<>(batch));
                    batch.clear();
                    lastFlushAt = System.currentTimeMillis();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                addError("OpenSearch writer loop error", e);
            }
        }

        if (!batch.isEmpty()) {
            sendWithRetry(batch);
        }
    }

    private void sendWithRetry(List<ILoggingEvent> events) {
        try {
            List<BulkPayloadBuilder.BulkItem> items = payloadBuilder.buildItems(events);
            attemptSend(items);
        } catch (Exception e) {
            reportFailure("Failed to build/send OpenSearch batch", e);
            addError("Failed to build/send OpenSearch batch", e);
        }
    }

    private void attemptSend(List<BulkPayloadBuilder.BulkItem> items) {
        List<BulkPayloadBuilder.BulkItem> current = items;
        for (int attempt = 0; attempt <= retryPolicy.maxRetries(); attempt++) {
            try {
                String payload = payloadBuilder.buildPayload(current);
                mirrorPayload(payload);
                SendResult result = sender.send(current, payload, retryPolicy.retryPartialFailures());
                if (result.isSuccess()) {
                    return;
                }
                if (result.retryableItems().isEmpty()) {
                    reportFailure("OpenSearch fatal failure: " + result.message(), result.cause());
                    deadLetterHandler.store(current, result.message(), result.cause());
                    addWarn("OpenSearch fatal failure: " + result.message());
                    return;
                }
                current = result.retryableItems();
                if (attempt >= retryPolicy.maxRetries()) {
                    reportFailure("OpenSearch retry exhausted: " + result.message(), result.cause());
                    deadLetterHandler.store(current, result.message(), result.cause());
                    addWarn("OpenSearch retry exhausted: " + result.message());
                    return;
                }
                long delay = retryPolicy.backoffMillis(attempt + 1);
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reportFailure("Interrupted during retry", e);
                deadLetterHandler.store(current, "Interrupted during retry", e);
                return;
            } catch (Exception e) {
                reportFailure("Unexpected send failure", e);
                deadLetterHandler.store(current, "Unexpected send failure", e);
                return;
            }
        }
    }

    private void flushRemaining() {
        if (queue == null || queue.isEmpty()) {
            return;
        }
        List<ILoggingEvent> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            sendWithRetry(remaining);
        }
    }

    private int estimateBytes(List<ILoggingEvent> events) {
        int size = 0;
        for (ILoggingEvent event : events) {
            size += event.getFormattedMessage() == null ? 128 : event.getFormattedMessage().length() + 256;
        }
        return size;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private void mirrorPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        if (logsToStderr) {
            System.err.println(payload);
        }
        if (loggerName != null && !loggerName.isBlank()) {
            Logger logger = LoggerFactory.getLogger(loggerName);
            logger.info(payload);
        }
    }

    private void reportFailure(String message, Throwable cause) {
        if (errorsToStderr) {
            if (cause == null) {
                System.err.println(message);
            } else {
                System.err.println(message + " - " + cause.getMessage());
            }
        }
        if (errorLoggerName != null && !errorLoggerName.isBlank()) {
            Logger logger = LoggerFactory.getLogger(errorLoggerName);
            if (cause == null) {
                logger.error(message);
            } else {
                logger.error(message, cause);
            }
        }
    }

    public void setUrl(String url) { this.url = url; }
    public void setIndex(String index) { this.index = index; }
    public void setUsername(String username) { this.username = username; }
    public void setPassword(String password) { this.password = password; }
    public void setApp(String app) { this.app = app; }
    public void setEnv(String env) { this.env = env; }
    public void setMaxBatchBytes(int maxBatchBytes) { this.maxBatchBytes = maxBatchBytes; }
    public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }
    public void setMaxMessageSize(int maxMessageSize) { this.maxMessageSize = maxMessageSize; }
    public void setFlushIntervalSeconds(int flushIntervalSeconds) { this.flushIntervalSeconds = flushIntervalSeconds; }
    public void setQueueSize(int queueSize) { this.queueSize = queueSize; }
    public void setConnectTimeoutMillis(int connectTimeoutMillis) { this.connectTimeoutMillis = connectTimeoutMillis; }
    public void setReadTimeoutMillis(int readTimeoutMillis) { this.readTimeoutMillis = readTimeoutMillis; }
    public void setRetryInitialDelayMillis(long retryInitialDelayMillis) { this.retryInitialDelayMillis = retryInitialDelayMillis; }
    public void setRetryMaxDelayMillis(long retryMaxDelayMillis) { this.retryMaxDelayMillis = retryMaxDelayMillis; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public void setRetryPartialFailures(boolean retryPartialFailures) { this.retryPartialFailures = retryPartialFailures; }
    public void setTrustAllSsl(boolean trustAllSsl) { this.trustAllSsl = trustAllSsl; }
    public void setDeadLetterLoggerName(String deadLetterLoggerName) { this.deadLetterLoggerName = deadLetterLoggerName; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public void setTimestampZone(String timestampZone) { this.timestampZone = timestampZone; }
    public void setTimestampFormat(String timestampFormat) { this.timestampFormat = timestampFormat; }
    public void setOperation(String operation) { this.operation = operation; }
    public void setIncludeMdc(boolean includeMdc) { this.includeMdc = includeMdc; }
    public void setIncludeKvp(boolean includeKvp) { this.includeKvp = includeKvp; }
    public void setIncludeCallerData(boolean includeCallerData) { this.includeCallerData = includeCallerData; }
    public void setRawJsonMessage(boolean rawJsonMessage) { this.rawJsonMessage = rawJsonMessage; }
    public void setLogsToStderr(boolean logsToStderr) { this.logsToStderr = logsToStderr; }
    public void setErrorsToStderr(boolean errorsToStderr) { this.errorsToStderr = errorsToStderr; }
    public void setLoggerName(String loggerName) { this.loggerName = loggerName; }
    public void setErrorLoggerName(String errorLoggerName) { this.errorLoggerName = errorLoggerName; }
    public void setHeaders(OpenSearchHeaders headers) { this.headers = headers; }
    public void setProperties(OpenSearchProperties properties) { this.properties = properties; }
}
