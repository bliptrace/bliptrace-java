package com.bliptrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Drives the {@link HttpTransport} retry loop with scripted HTTP results and a no-op
 * sleep, so no real network or real sleeping happens. We override the protected
 * {@code attempt} and {@code sleepMs} hooks to record what the transport did.
 */
class HttpTransportRetryTest {

    /** A transport whose HTTP attempts are scripted and whose sleeps are recorded. */
    static class ScriptedTransport extends HttpTransport {
        final Deque<Attempt> script = new ArrayDeque<>();
        final List<String> bodies = new ArrayList<>();
        final List<Long> sleeps = new ArrayList<>();
        int attempts = 0;

        ScriptedTransport(RetryPolicy policy) {
            super(policy);
        }

        ScriptedTransport queue(int status, int retryAfterSec) {
            script.add(new Attempt(status, retryAfterSec));
            return this;
        }

        @Override
        protected Attempt attempt(String url, String body, String key, long timeoutMillis) {
            attempts++;
            bodies.add(body);
            // Exhausted script means "keep returning the last scripted result".
            return script.size() > 1 ? script.poll() : script.peek();
        }

        @Override
        protected void sleepMs(long ms) {
            sleeps.add(ms); // never actually sleep
        }
    }

    private static Map<String, Object> envelope(String eventId) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("event_id", eventId);
        env.put("schema_version", "1.0");
        return env;
    }

    @Test
    void stopsImmediatelyOnSuccess() {
        ScriptedTransport t = new ScriptedTransport(new RetryPolicy(2, 100, 2000)).queue(202, 0);

        t.send("http://api", "k", envelope("e1"), 1000);

        assertEquals(1, t.attempts, "a 202 must not be retried");
        assertTrue(t.sleeps.isEmpty(), "no sleep on success");
    }

    @Test
    void doesNotRetryA4xx() {
        ScriptedTransport t = new ScriptedTransport(new RetryPolicy(2, 100, 2000)).queue(400, 0);

        t.send("http://api", "k", envelope("e1"), 1000);

        assertEquals(1, t.attempts, "a 400 is a bad request, never retried");
        assertTrue(t.sleeps.isEmpty());
    }

    @Test
    void retriesA503ThenSucceeds() {
        ScriptedTransport t = new ScriptedTransport(new RetryPolicy(2, 100, 2000))
                .queue(503, 0)
                .queue(202, 0);

        t.send("http://api", "k", envelope("e1"), 1000);

        assertEquals(2, t.attempts);
        assertEquals(1, t.sleeps.size(), "one backoff between the two attempts");
    }

    @Test
    void givesUpAfterMaxRetriesWhenAlwaysFailing() {
        ScriptedTransport t = new ScriptedTransport(new RetryPolicy(2, 100, 2000)).queue(500, 0);

        t.send("http://api", "k", envelope("e1"), 1000);

        // 1 initial + 2 retries = 3 attempts; 2 sleeps between them.
        assertEquals(3, t.attempts);
        assertEquals(2, t.sleeps.size());
    }

    @Test
    void retriesATransportErrorRepresentedAsStatusZero() {
        ScriptedTransport t = new ScriptedTransport(new RetryPolicy(1, 100, 2000)).queue(0, 0);

        t.send("http://api", "k", envelope("e1"), 1000);

        assertEquals(2, t.attempts, "1 initial + 1 retry on transport error");
        assertEquals(1, t.sleeps.size());
    }

    @Test
    void replaysTheIdenticalBodyAndEventIdAcrossAttempts() {
        ScriptedTransport t = new ScriptedTransport(new RetryPolicy(2, 100, 2000)).queue(500, 0);

        t.send("http://api", "k", envelope("constant-event-id"), 1000);

        assertEquals(3, t.attempts);
        String first = t.bodies.get(0);
        for (String b : t.bodies) {
            assertEquals(first, b, "the same serialized body must be replayed each attempt");
        }
        assertTrue(first.contains("\"event_id\":\"constant-event-id\""),
                "event_id stays constant so the server dedupes retries");
    }

    @Test
    void recordedBackoffGrowsExponentiallyWithDeterministicJitter() {
        // Pin the jitter to 0.5 (no jitter) so recorded delays are the clean
        // exponential 100, 200 — proving growth and cap behaviour in the live loop.
        RetryPolicy policy = new RetryPolicy(2, 100, 2000);
        ScriptedTransport t = new ScriptedTransport(policy) {
            @Override
            protected long delayMs(int attempt, int retryAfterSec) {
                return policy.delayMs(attempt, retryAfterSec, () -> 0.5);
            }
        }.queue(500, 0);

        t.send("http://api", "k", envelope("e1"), 1000);

        assertEquals(List.of(100L, 200L), t.sleeps);
    }

    @Test
    void honoursServerRetryAfterCappedAndNeverThrows() {
        // Retry-After of 999s would be huge, but the cap pins each backoff to 2000ms.
        ScriptedTransport t = new ScriptedTransport(new RetryPolicy(2, 100, 2000))
                .queue(429, 999)
                .queue(429, 999)
                .queue(202, 0);

        t.send("http://api", "k", envelope("e1"), 1000);

        assertEquals(3, t.attempts);
        for (long s : t.sleeps) {
            assertTrue(s <= 2000L, "every backoff is capped at capMs");
        }
        assertFalse(t.sleeps.isEmpty());
    }
}
