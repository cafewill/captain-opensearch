package com.cube.opensearch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
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
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean writerActive = new AtomicBoolean(false);

    private String url;
    private String index;
    private String type;
    private String loggerName;
    private String errorLoggerName;

    private int sleepTime = 250;
    private int maxRetries = 3;
    private int connectTimeout = 30_000;
    private int readTimeout = 30_000;
    private boolean logsToStderr = false;
    private boolean errorsToStderr = false;
    private boolean includeCallerData = false;
    private boolean includeMdc = true;
    private boolean rawJsonMessage = false;
    private int maxQueueSize = 100 * 1024 * 1024;
    private OpenSearchAuthentication authentication;
    private int maxMessageSize = -1;
    private String keyPrefix = "";
    private boolean objectSerialization = false;
    private String autoStackTraceLevel = "OFF";
    private int resolvedAutoStackTraceLevelInt = Level.OFF.levelInt;
    private String operation = "create";
    private boolean includeKvp = false;
    private int maxBatchSize = -1;
    private String timestampFormat;
    private boolean trustAllSsl = true;
    private boolean includeStructuredArgs = false;
    private boolean persistentWriterThread = false;

    private OpenSearchHeaders headers;
    private OpenSearchProperties properties;

    private BlockingQueue<ILoggingEvent> queue;
    private Thread writerThread;
    private BulkPayloadBuilder payloadBuilder;
    private OpenSearchSender sender;

    public AbstractOpenSearchAppender() {
    }

    @Override
    public void start() {
        if (!validateConfiguration()) {
            return;
        }
        if (objectSerialization) {
            objectMapper.findAndRegisterModules();
        }

        int queueCapacity = Math.max(100, maxQueueSize / 512);
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.payloadBuilder = new BulkPayloadBuilder(
                getContext(),
                objectMapper,
                index,
                type,
                timestampFormat,
                operation,
                includeMdc,
                includeKvp,
                includeCallerData,
                rawJsonMessage,
                maxMessageSize,
                properties,
                keyPrefix,
                objectSerialization,
                includeStructuredArgs
        );
        this.sender = new OpenSearchSender(
                objectMapper,
                url,
                authentication,
                connectTimeout,
                readTimeout,
                trustAllSsl,
                headers
        );
        running.set(true);
        if (persistentWriterThread) {
            startWriterIfNeeded();
        }
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
        if (resolvedAutoStackTraceLevelInt < Level.OFF.levelInt
                && eventObject instanceof LoggingEvent le
                && le.getThrowableProxy() == null
                && le.getLevel() != null
                && le.getLevel().levelInt >= resolvedAutoStackTraceLevelInt) {
            Exception autoEx = new Exception("auto generated stacktrace");
            autoEx.setStackTrace(le.getCallerData());
            le.setThrowableProxy(new ThrowableProxy(autoEx));
        }
        if (!queue.offer(eventObject)) {
            addWarn("OpenSearch queue is full. dropping log event.");
            return;
        }
        if (!persistentWriterThread) {
            startWriterIfNeeded();
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
        if (sleepTime < 100) {
            addWarn("sleepTime < 100. using minimum 100");
            sleepTime = 100;
        }
        if (maxQueueSize <= 0) {
            addWarn("maxQueueSize <= 0. using default 104857600");
            maxQueueSize = 100 * 1024 * 1024;
        }
        return valid;
    }

    private void startWriterIfNeeded() {
        if (!running.get() || !writerActive.compareAndSet(false, true)) {
            return;
        }
        writerThread = new Thread(this::runLoop, getThreadName());
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void runLoop() {
        List<ILoggingEvent> batch = new ArrayList<>();
        long lastFlushAt = System.currentTimeMillis();

        try {
            while (running.get() || !queue.isEmpty()) {
                try {
                    ILoggingEvent event = queue.poll(sleepTime, TimeUnit.MILLISECONDS);
                    if (event != null) {
                        batch.add(event);
                    }

                    boolean timeToFlush = (System.currentTimeMillis() - lastFlushAt) >= sleepTime;
                    boolean countToFlush = maxBatchSize > 0 && batch.size() >= maxBatchSize;
                    if (!batch.isEmpty() && (timeToFlush || countToFlush)) {
                        sendWithRetry(new ArrayList<>(batch));
                        batch.clear();
                        lastFlushAt = System.currentTimeMillis();
                    }

                    if (!persistentWriterThread && running.get() && queue.isEmpty() && batch.isEmpty()) {
                        break;
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
        } finally {
            writerActive.set(false);
            if (!persistentWriterThread && running.get() && queue != null && !queue.isEmpty()) {
                startWriterIfNeeded();
            }
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
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String payload = payloadBuilder.buildPayload(current);
                mirrorPayload(payload);
                SendResult result = sender.send(current, payload);
                if (result.isSuccess()) {
                    return;
                }
                if (result.retryableItems().isEmpty()) {
                    reportFailure("OpenSearch fatal failure: " + result.message(), result.cause());
                    addWarn("OpenSearch fatal failure: " + result.message());
                    return;
                }
                current = result.retryableItems();
                if (attempt >= maxRetries) {
                    reportFailure("OpenSearch retry exhausted: " + result.message(), result.cause());
                    addWarn("OpenSearch retry exhausted: " + result.message());
                    return;
                }
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reportFailure("Interrupted during retry", e);
                return;
            } catch (Exception e) {
                reportFailure("Unexpected send failure", e);
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
    public void setType(String type) { this.type = type; }
    public void setLoggerName(String loggerName) { this.loggerName = loggerName; }
    public void setErrorLoggerName(String errorLoggerName) { this.errorLoggerName = errorLoggerName; }
    public void setSleepTime(int sleepTime) { this.sleepTime = sleepTime; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = Math.max(0, maxRetries); }
    public void setConnectTimeout(int connectTimeout) { this.connectTimeout = connectTimeout; }
    public void setReadTimeout(int readTimeout) { this.readTimeout = readTimeout; }
    public void setLogsToStderr(boolean logsToStderr) { this.logsToStderr = logsToStderr; }
    public void setErrorsToStderr(boolean errorsToStderr) { this.errorsToStderr = errorsToStderr; }
    public void setIncludeCallerData(boolean includeCallerData) { this.includeCallerData = includeCallerData; }
    public void setIncludeMdc(boolean includeMdc) { this.includeMdc = includeMdc; }
    public void setRawJsonMessage(boolean rawJsonMessage) { this.rawJsonMessage = rawJsonMessage; }
    public void setMaxQueueSize(int maxQueueSize) { this.maxQueueSize = maxQueueSize; }
    public void setAuthentication(OpenSearchAuthentication authentication) { this.authentication = authentication; }
    public void setMaxMessageSize(int maxMessageSize) { this.maxMessageSize = maxMessageSize; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix == null ? "" : keyPrefix; }
    public void setObjectSerialization(boolean objectSerialization) { this.objectSerialization = objectSerialization; }
    public void setAutoStackTraceLevel(String level) {
        this.autoStackTraceLevel = level;
        this.resolvedAutoStackTraceLevelInt = Level.toLevel(level, Level.OFF).levelInt;
    }
    public void setOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            addWarn("Invalid value for [operation], using create");
            this.operation = "create";
            return;
        }
        String lower = operation.trim().toLowerCase();
        if (!lower.equals("index") && !lower.equals("create")) {
            addWarn("Bulk-only appender supports only [index] or [create] for [operation]: " + operation + ", using create");
            this.operation = "create";
            return;
        }
        this.operation = lower;
    }
    public void setIncludeKvp(boolean includeKvp) { this.includeKvp = includeKvp; }
    public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }
    public void setTimestampFormat(String timestampFormat) { this.timestampFormat = timestampFormat; }
    public void setTrustAllSsl(boolean trustAllSsl) { this.trustAllSsl = trustAllSsl; }
    public void setIncludeStructuredArgs(boolean includeStructuredArgs) { this.includeStructuredArgs = includeStructuredArgs; }
    public void setPersistentWriterThread(boolean persistentWriterThread) { this.persistentWriterThread = persistentWriterThread; }
    public void setHeaders(OpenSearchHeaders headers) { this.headers = headers; }
    public void setProperties(OpenSearchProperties properties) { this.properties = properties; }
}
