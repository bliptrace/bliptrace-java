package com.g8scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryPolicyTest {

    @ParameterizedTest(name = "status {0} retryable={1}")
    @CsvSource({
            "0,   true",   // transport error / timeout
            "408, true",   // request timeout
            "429, true",   // too many requests
            "500, true",   // server error
            "502, true",   // bad gateway
            "503, true",   // service unavailable
            "200, false",  // ok
            "202, false",  // accepted
            "400, false",  // bad request
            "403, false",  // forbidden
            "404, false",  // not found
    })
    void retriesTransportErrors408_429And5xxButNotSuccessOrOther4xx(int status, boolean expected) {
        RetryPolicy policy = new RetryPolicy(5, 100, 2000);
        assertEquals(expected, policy.isRetryable(status));
    }

    @Test
    void stopsRetryingOnceTheCapOnAttemptsIsReached() {
        RetryPolicy policy = new RetryPolicy(2, 100, 2000);
        assertTrue(policy.shouldRetry(503, 0));
        assertTrue(policy.shouldRetry(503, 1));
        assertFalse(policy.shouldRetry(503, 2)); // exhausted
    }

    @Test
    void neverRetriesANonRetryableStatusEvenWithAttemptsRemaining() {
        RetryPolicy policy = new RetryPolicy(5, 100, 2000);
        assertFalse(policy.shouldRetry(400, 0));
        assertFalse(policy.shouldRetry(200, 0));
    }

    @Test
    void growsTheDelayExponentiallyAndNeverExceedsTheCap() {
        RetryPolicy policy = new RetryPolicy(2, 100, 2000);
        DoubleSupplier noJitter = () -> 0.5; // 2*0.5-1 = 0 → no jitter

        assertEquals(100L, policy.delayMs(0, 0, noJitter));
        assertEquals(200L, policy.delayMs(1, 0, noJitter));
        assertEquals(400L, policy.delayMs(2, 0, noJitter));
        assertEquals(800L, policy.delayMs(3, 0, noJitter));
        assertEquals(2000L, policy.delayMs(8, 0, noJitter)); // capped
        assertEquals(2000L, policy.delayMs(60, 0, noJitter)); // no overflow, pinned to cap
    }

    @Test
    void keepsJitteredDelayWithinPlusMinus25PercentOfTheExponentialBase() {
        // capMs large so the cap doesn't clip the jitter band; attempt 3 → base 800ms.
        RetryPolicy policy = new RetryPolicy(5, 100, 100_000);
        for (double r : new double[] {0.0, 0.999, 0.5, 0.25, 0.75}) {
            long d = policy.delayMs(3, 0, () -> r);
            assertTrue(d >= 600L, "delay " + d + " below -25% bound");
            assertTrue(d <= 1000L, "delay " + d + " above +25% bound");
        }
    }

    @Test
    void jitterAtExtremesHitsExactlyPlusMinus25Percent() {
        RetryPolicy policy = new RetryPolicy(5, 100, 100_000);
        assertEquals(600L, policy.delayMs(3, 0, () -> 0.0));   // 800 - 25%
        assertEquals(1000L, policy.delayMs(3, 0, () -> 1.0));  // 800 + 25%
    }

    @Test
    void honoursAServerRetryAfterInSecondsCapped() {
        RetryPolicy policy = new RetryPolicy(2, 100, 5000);
        // Retry-After wins over exponential; jitter is irrelevant here.
        assertEquals(2000L, policy.delayMs(0, 2, () -> 0.123));   // 2s honoured
        assertEquals(5000L, policy.delayMs(0, 999, () -> 0.9));   // hostile value capped
    }

    @Test
    void retryAfterOfZeroFallsBackToExponential() {
        RetryPolicy policy = new RetryPolicy(2, 100, 2000);
        assertEquals(100L, policy.delayMs(0, 0, () -> 0.5));
    }

    @Test
    void delayIsNeverNegative() {
        RetryPolicy policy = new RetryPolicy(5, 100, 100_000);
        assertTrue(policy.delayMs(0, 0, () -> 0.0) >= 0L);
    }

    @Test
    void buildsFromSdkConfig() {
        RetryPolicy policy = RetryPolicy.fromConfig(
                Config.builder().key("k").maxRetries(7).retryBaseMs(50).retryCapMs(9000).build());
        assertEquals(7, policy.maxRetries());
        assertEquals(50, policy.baseMs());
        assertEquals(9000, policy.capMs());
    }

    @Test
    void defaultsMatchTheSpec() {
        RetryPolicy policy = new RetryPolicy();
        assertEquals(2, policy.maxRetries());
        assertEquals(100, policy.baseMs());
        assertEquals(2000, policy.capMs());
    }
}
