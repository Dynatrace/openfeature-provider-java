package com.dynatrace.openfeature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the KB section 2.1 invariant: {@code max fetch duration < shortest poll interval}.
 *
 * <p>Two polls must never be in flight at once. That is not a coincidence of the current numbers;
 * it is a property any change to the request timeout, the attempt count or the backoff has to
 * preserve. If this test fails, the change broke the bound; fix the numbers, don't relax the test.
 */
class RetryBoundInvariantTest {

    @Test
    void maxFetchDurationIsShorterThanTheShortestPollInterval() {
        long maxFetchMs = RetryStrategy.MAX_ATTEMPTS * CdnFetcher.REQUEST_TIMEOUT.toMillis()
                + RetryStrategy.MAX_TOTAL_BACKOFF_MS;
        long shortestPollMs = DynatraceProvider.POLL_INTERVAL_SSE_DISCONNECTED.toMillis();

        assertTrue(maxFetchMs < shortestPollMs,
                "max fetch duration " + maxFetchMs + "ms must be under the shortest poll interval "
                        + shortestPollMs + "ms (KB 2.1); got "
                        + RetryStrategy.MAX_ATTEMPTS + " attempts x "
                        + CdnFetcher.REQUEST_TIMEOUT.toMillis() + "ms + "
                        + RetryStrategy.MAX_TOTAL_BACKOFF_MS + "ms backoff");
    }

    @Test
    void currentNumbersMatchTheSpecArithmetic() {
        // 4s + 0.12s + 4s = 8.12s < 10s
        assertTrue(CdnFetcher.REQUEST_TIMEOUT.toSeconds() == 4, "request timeout should be 4s");
        assertTrue(RetryStrategy.MAX_ATTEMPTS == 2, "max attempts should be 2");
        assertTrue(RetryStrategy.MAX_TOTAL_BACKOFF_MS == 120, "max total backoff should be 120ms");
    }
}
