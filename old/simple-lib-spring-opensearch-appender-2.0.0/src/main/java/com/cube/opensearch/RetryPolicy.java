package com.cube.opensearch;

public final class RetryPolicy {
    private int maxRetries = 3;
    private long initialDelayMillis = 500L;
    private long maxDelayMillis = 10_000L;
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
        long raw = (long) (initialDelayMillis * Math.pow(2D, Math.max(0, attempt - 1)));
        return Math.min(raw, maxDelayMillis);
    }
}
