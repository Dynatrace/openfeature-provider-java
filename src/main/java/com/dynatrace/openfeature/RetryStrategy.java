package com.dynatrace.openfeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Callable;

/**
 * Bounded retry for transient HTTP failures: one retry after a short jittered delay.
 *
 * <p>Retries on 5xx and network/timeout errors. Does not retry 4xx
 * (429 is handled separately by {@link CdnFetcher}, and ends the fetch outright).
 *
 * <p>Parameters per KB section 2.4:
 * <ul>
 *   <li>Max attempts: 2 (initial attempt + 1 retry)</li>
 *   <li>Backoff: 100ms before the retry</li>
 *   <li>Jitter: +0-20% additive random</li>
 * </ul>
 *
 * <p>One retry, not a loop: a fetch must not outlive the shortest poll interval (KB section 2.1),
 * so beyond the single retry recovery is the next poll tick's job. {@code RetryBoundInvariantTest}
 * guards the arithmetic.
 */
final class RetryStrategy {

    private static final Logger log = LoggerFactory.getLogger(RetryStrategy.class);

    static final int MAX_ATTEMPTS = 2;
    static final long BASE_DELAY_MS = 100L;
    static final double MAX_JITTER_FACTOR = 0.20;

    /** Worst-case sleep across a whole fetch; an input to the fetch-duration invariant. */
    static final long MAX_TOTAL_BACKOFF_MS =
            (long) ((MAX_ATTEMPTS - 1) * BASE_DELAY_MS * (1 + MAX_JITTER_FACTOR));

    private final Random random;
    private final Sleeper sleeper;

    RetryStrategy() {
        this(new Random(), Thread::sleep);
    }

    // visible for testing
    RetryStrategy(Random random, Sleeper sleeper) {
        this.random = random;
        this.sleeper = sleeper;
    }

    /**
     * Executes the given request with retry logic.
     *
     * @return the result of a successful or non-retryable request
     * @throws IOException if all attempts fail with network errors
     */
    FetchResult execute(Callable<FetchResult> request) throws IOException {
        IOException lastNetworkError = null;
        FetchResult lastResult = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                FetchResult result = request.call();
                lastResult = result;

                if (!shouldRetry(result.getStatusCode())) {
                    return result;
                }

                log.warn("attempt {}/{} returned HTTP {}{}",
                        attempt, MAX_ATTEMPTS, result.getStatusCode(),
                        attempt < MAX_ATTEMPTS ? "; retrying" : "; giving up");
            } catch (IOException e) {
                lastNetworkError = e;
                log.warn("attempt {}/{} failed: {}{}",
                        attempt, MAX_ATTEMPTS, e.getMessage(),
                        attempt < MAX_ATTEMPTS ? "; retrying" : "; giving up");
            } catch (Exception e) {
                throw new IOException("unexpected error during fetch", e);
            }

            if (attempt < MAX_ATTEMPTS) {
                long jitter = (long) (BASE_DELAY_MS * random.nextDouble() * MAX_JITTER_FACTOR);
                try {
                    sleeper.sleep(BASE_DELAY_MS + jitter);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted during retry backoff", ie);
                }
            }
        }

        if (lastResult != null) {
            return lastResult;
        }
        throw lastNetworkError != null ? lastNetworkError : new IOException("all retry attempts failed");
    }

    static boolean shouldRetry(int status) {
        return status >= 500 && status < 600;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }
}
