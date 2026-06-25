package com.g8scope;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Decides whether a failed ingest attempt should be retried, and how long to wait.
 *
 * <p>The SDK owns "the backend might be briefly down or shedding (429)". Strategy:
 * exponential backoff (base &times; 2^attempt), &plusmn;25% jitter to avoid a synchronised
 * thundering herd, and a hard cap on both attempts and delay so capturing can never
 * block the monitored app for long. Retries are only safe because the envelope's
 * {@code event_id} is reused across attempts and the ingest API dedupes on it
 * (spec &sect;10).</p>
 *
 * <p>Pure and immutable: no I/O, no sleeping — that lives in the transport.</p>
 */
public final class RetryPolicy {

    /** Status used to represent a transport error / timeout (no HTTP response). */
    public static final int TRANSPORT_ERROR = 0;

    private final int maxRetries;
    private final int baseMs;
    private final int capMs;

    public RetryPolicy() {
        this(2, 100, 2000);
    }

    public RetryPolicy(int maxRetries, int baseMs, int capMs) {
        this.maxRetries = maxRetries;
        this.baseMs = baseMs;
        this.capMs = capMs;
    }

    /** Build from the SDK config (framework adapters pass their own values). */
    public static RetryPolicy fromConfig(Config config) {
        return new RetryPolicy(config.maxRetries(), config.retryBaseMs(), config.retryCapMs());
    }

    public int maxRetries() {
        return maxRetries;
    }

    public int baseMs() {
        return baseMs;
    }

    public int capMs() {
        return capMs;
    }

    /**
     * Should we retry after seeing {@code status}, having already done {@code attempt}
     * retries?
     *
     * <p>Retryable: transport errors / timeouts (status 0), 408, 429, and any 5xx.
     * Never retry a 2xx (success) or other 4xx (the request itself is bad).</p>
     */
    public boolean shouldRetry(int status, int attempt) {
        if (attempt >= maxRetries) {
            return false;
        }
        return isRetryable(status);
    }

    public boolean isRetryable(int status) {
        return status == TRANSPORT_ERROR || status == 408 || status == 429 || status >= 500;
    }

    /**
     * Delay before the next attempt, in milliseconds, using a real random jitter source.
     */
    public long delayMs(int attempt, int retryAfterSec) {
        return delayMs(attempt, retryAfterSec, () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Delay before the next attempt, in milliseconds.
     *
     * <p>A server-sent {@code Retry-After} (seconds, {@code 0} = none) wins, capped to
     * {@code capMs} so a hostile or misconfigured header can't stall the app. Otherwise:
     * exponential backoff with &plusmn;25% jitter, capped at {@code capMs}. {@code jitter}
     * returns a double in {@code [0,1)} and is injectable for deterministic tests.</p>
     */
    public long delayMs(int attempt, int retryAfterSec, DoubleSupplier jitter) {
        if (retryAfterSec > 0) {
            return Math.min(capMs, (long) retryAfterSec * 1000L);
        }

        // Clamp the shift so 2^attempt can never overflow a long; the result is
        // capped at capMs anyway, so a large attempt simply pins to the cap.
        int shift = Math.min(62, Math.max(0, attempt));
        long exp = Math.min((long) capMs, (long) baseMs * (1L << shift));
        double rand = jitter.getAsDouble();
        long delta = Math.round(exp * 0.25 * (2 * rand - 1)); // ±25%

        return Math.max(0L, Math.min((long) capMs, exp + delta));
    }
}
