package com.cube.opensearch;

public final class RetryPolicy {
    private int maxRetries = 3;
    private long initialDelayMillis = 500L;
    private long maxDelayMillis = 5_000L;
    private boolean retryPartialFailures = true;

    public int maxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
    }

    public long initialDelayMillis() {
        return initialDelayMillis;
    }

    public void setInitialDelayMillis(long initialDelayMillis) {
        this.initialDelayMillis = Math.max(0L, initialDelayMillis);
    }

    public long maxDelayMillis() {
        return maxDelayMillis;
    }

    public void setMaxDelayMillis(long maxDelayMillis) {
        this.maxDelayMillis = Math.max(0L, maxDelayMillis);
    }

    public boolean retryPartialFailures() {
        return retryPartialFailures;
    }

    public void setRetryPartialFailures(boolean retryPartialFailures) {
        this.retryPartialFailures = retryPartialFailures;
    }

    public long backoffMillis(int attempt) {
        if (attempt <= 0) {
            return 0L;
        }
        long delay = initialDelayMillis;
        for (int i = 1; i < attempt; i++) {
            delay = Math.min(maxDelayMillis, delay * 2L);
        }
        return delay;
    }
}
