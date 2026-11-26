package com.bliptrace;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Default transport: POST the envelope to {endpoint}/v1/ingest using the JDK's
 * built-in {@link HttpClient} (no third-party HTTP library). Swallows all errors —
 * capturing must never break the monitored app.
 *
 * <p>Resilient to a briefly-down or load-shedding ingest API: a retryable result
 * (transport error/timeout, 408, 429 or 5xx) is retried with exponential backoff and
 * jitter per {@link RetryPolicy}, honouring a numeric {@code Retry-After} header. The
 * <em>same</em> serialized body is replayed on every attempt, so the {@code event_id}
 * stays constant and the server's idempotency dedupe makes retries safe — a retry
 * never creates a duplicate occurrence (spec §10).</p>
 */
public class HttpTransport implements Transport {

    private final HttpClient client;
    private final RetryPolicy policy;

    public HttpTransport() {
        this(HttpClient.newHttpClient(), new RetryPolicy());
    }

    public HttpTransport(HttpClient client) {
        this(client, new RetryPolicy());
    }

    public HttpTransport(RetryPolicy policy) {
        this(HttpClient.newHttpClient(), policy);
    }

    public HttpTransport(HttpClient client, RetryPolicy policy) {
        this.client = client != null ? client : HttpClient.newHttpClient();
        this.policy = policy != null ? policy : new RetryPolicy();
    }

    /** Result of one HTTP attempt: the HTTP status (0 on transport error) and the
     * parsed numeric {@code Retry-After} in seconds (0 = none). */
    protected static final class Attempt {
        final int status;
        final int retryAfterSec;

        Attempt(int status, int retryAfterSec) {
            this.status = status;
            this.retryAfterSec = retryAfterSec;
        }
    }

    @Override
    public void send(String endpoint, String key, Map<String, Object> envelope, long timeoutMillis) {
        try {
            String base = endpoint == null ? "" : endpoint.replaceAll("/+$", "");
            String url = base + "/v1/ingest";
            // Serialize ONCE and replay the same body on every attempt, so the
            // event_id is identical across retries (server dedupes on it).
            String body = Json.encode(envelope);

            int attempt = 0;
            while (true) {
                Attempt result = attempt(url, body, key, timeoutMillis);
                if (!policy.shouldRetry(result.status, attempt)) {
                    return;
                }
                sleepMs(delayMs(attempt, result.retryAfterSec));
                attempt++;
            }
        } catch (Throwable ignored) {
            // Capturing must never break the monitored app.
        }
    }

    /**
     * Perform one HTTP attempt. Returns the HTTP status (0 on a transport
     * error/timeout) and any numeric {@code Retry-After} seconds. Protected and
     * overridable so tests can script responses without real network.
     */
    protected Attempt attempt(String url, String body, String key, long timeoutMillis) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1, timeoutMillis)))
                    .header("Content-Type", "application/json")
                    .header("X-Ingest-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return new Attempt(response.statusCode(), parseRetryAfter(response));
        } catch (Throwable t) {
            // Transport error / timeout — represented as status 0 (retryable).
            return new Attempt(RetryPolicy.TRANSPORT_ERROR, 0);
        }
    }

    /** Parse a numeric {@code Retry-After} (seconds) header, or 0 if absent/invalid. */
    private static int parseRetryAfter(HttpResponse<?> response) {
        Optional<String> value = response.headers().firstValue("Retry-After");
        if (value.isEmpty()) {
            return 0;
        }
        try {
            int seconds = Integer.parseInt(value.get().trim());
            return Math.max(0, seconds);
        } catch (NumberFormatException e) {
            return 0; // non-numeric (HTTP-date form) is ignored
        }
    }

    /**
     * Backoff before the next attempt, in milliseconds, delegating to the policy with
     * a real random jitter source. Protected and overridable so tests can pin the
     * jitter and assert on the exact recorded delays.
     */
    protected long delayMs(int attempt, int retryAfterSec) {
        return policy.delayMs(attempt, retryAfterSec);
    }

    /** Sleep between attempts. Protected and overridable so tests run without delay. */
    protected void sleepMs(long ms) {
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
