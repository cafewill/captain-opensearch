package com.cube.opensearch;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
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
    private String env = "local";
    private int maxBatchBytes = 1_000_000;
    private int flushIntervalSeconds = 1;
    private int queueSize = 8192;
    private int connectTimeoutMillis = 5_000;
    private int readTimeoutMillis = 10_000;
    private long retryInitialDelayMillis = 500L;
    private long retryMaxDelayMillis = 5_000L;
    private int maxRetries = 3;
    private boolean retryPartialFailures = true;
    private boolean trustAllSsl = true; // 내부망 전용 의도 유지
    private String deadLetterLoggerName = "com.cube.opensearch.deadletter";
    private String instanceId;

    private BlockingQueue<ILoggingEvent> queue;
    private Thread writerThread;
    private BulkPayloadBuilder payloadBuilder;
    private OpenSearchSender sender;
    private DeadLetterHandler deadLetterHandler;

    @Override
    public void start() {
        if (!validateConfiguration()) {
            return;
        }
        retryPolicy.setMaxRetries(maxRetries);
        retryPolicy.setInitialDelayMillis(retryInitialDelayMillis);
        retryPolicy.setMaxDelayMillis(retryMaxDelayMillis);
        retryPolicy.setRetryPartialFailures(retryPartialFailures);

        instanceId = Objects.requireNonNullElseGet(instanceId, OpenSearchSender::resolveInstanceId);
        queue = new LinkedBlockingQueue<>(queueSize);
        payloadBuilder = new BulkPayloadBuilder(objectMapper, index, app, env, instanceId);
        sender = new OpenSearchSender(objectMapper, url, username, password, connectTimeoutMillis, readTimeoutMillis, trustAllSsl);
        deadLetterHandler = new Slf4jDeadLetterHandler(deadLetterLoggerName);

        running.set(true);
        writerThread = new Thread(this::runLoop, getThreadName());
        writerThread.setDaemon(true);
        writerThread.start();
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        running.set(false);
        if (writerThread != null) {
            writerThread.interrupt();
        }
        flushRemaining();
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        if (!isStarted() || queue == null) {
            return;
        }
        eventObject.prepareForDeferredProcessing();
        if (!queue.offer(eventObject)) {
            addWarn("OpenSearch queue full. Dropping log event: " + eventObject.getFormattedMessage());
        }
    }

    protected abstract String getThreadName();

    private boolean validateConfiguration() {
        if (url == null || url.isBlank()) {
            addError("OpenSearch appender url is required");
            return false;
        }
        if (index == null || index.isBlank()) {
            addError("OpenSearch appender index is required");
            return false;
        }
        if (queueSize <= 0 || flushIntervalSeconds <= 0 || maxBatchBytes <= 0) {
            addError("queueSize, flushIntervalSeconds and maxBatchBytes must be greater than zero");
            return false;
        }
        return true;
    }

    private void runLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                ILoggingEvent head = queue.poll(flushIntervalSeconds, TimeUnit.SECONDS);
                if (head == null) {
                    continue;
                }
                List<ILoggingEvent> events = new ArrayList<>();
                events.add(head);
                int estimatedBytes = Math.max(256, head.getFormattedMessage() == null ? 256 : head.getFormattedMessage().length() * 2);
                while (estimatedBytes < maxBatchBytes) {
                    ILoggingEvent next = queue.poll();
                    if (next == null) {
                        break;
                    }
                    events.add(next);
                    estimatedBytes += Math.max(256, next.getFormattedMessage() == null ? 256 : next.getFormattedMessage().length() * 2);
                }
                sendWithRetry(events);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                addError("Unexpected OpenSearch appender error: " + e.getMessage(), e);
            }
        }
    }

    private void sendWithRetry(List<ILoggingEvent> events) {
        try {
            List<BulkPayloadBuilder.BulkItem> items = payloadBuilder.buildItems(events);
            attemptSend(items);
        } catch (Exception e) {
            addError("Failed to serialize bulk payload: " + e.getMessage(), e);
        }
    }

    private void attemptSend(List<BulkPayloadBuilder.BulkItem> initialItems) {
        List<BulkPayloadBuilder.BulkItem> current = initialItems;
        for (int attempt = 0; attempt <= retryPolicy.maxRetries(); attempt++) {
            try {
                String payload = payloadBuilder.buildPayload(current);
                SendResult result = sender.send(current, payload, retryPolicy.retryPartialFailures());
                if (result.isSuccess()) {
                    return;
                }
                if (attempt >= retryPolicy.maxRetries()) {
                    deadLetterHandler.store(result.retryableItems().isEmpty() ? current : result.retryableItems(),
                            result.message(), result.cause());
                    addError("OpenSearch send failed after retries: " + result.message(), result.cause());
                    return;
                }
                current = result.retryableItems().isEmpty() ? current : result.retryableItems();
                long backoff = retryPolicy.backoffMillis(attempt + 1);
                addWarn("OpenSearch send retry attempt=" + (attempt + 1) + " items=" + current.size() + " reason=" + result.message());
                Thread.sleep(backoff);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (attempt >= retryPolicy.maxRetries()) {
                    deadLetterHandler.store(current, "unexpected send failure", e);
                    addError("OpenSearch send failed after retries: " + e.getMessage(), e);
                    return;
                }
                try {
                    Thread.sleep(retryPolicy.backoffMillis(attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void flushRemaining() {
        if (queue == null || queue.isEmpty()) {
            return;
        }
        List<ILoggingEvent> drained = new ArrayList<>();
        queue.drainTo(drained);
        if (!drained.isEmpty()) {
            sendWithRetry(drained);
        }
    }

    public void setUrl(String url) { this.url = url; }
    public void setIndex(String index) { this.index = index; }
    public void setUsername(String username) { this.username = username; }
    public void setPassword(String password) { this.password = password; }
    public void setApp(String app) { this.app = app; }
    public void setEnv(String env) { this.env = env; }
    public void setMaxBatchBytes(int maxBatchBytes) { this.maxBatchBytes = maxBatchBytes; }
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
}
