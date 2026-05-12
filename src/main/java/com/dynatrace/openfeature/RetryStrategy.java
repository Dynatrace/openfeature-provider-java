package com.dynatrace.openfeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Callable;

/**
 * Exponential backoff with jitter for transient HTTP failures.
 *
 * <p>Retries on 5xx and network/timeout errors. Does not retry 4xx
 * (429 is handled separately by {@link CdnFetcher}).
 *
 * <p>Parameters per KB section 2.4:
 * <ul>
 *   <li>Base delay: 100ms</li>
 *   <li>Multiplier: 2x</li>
 *   <li>Jitter: +0-20% additive random</li>
 *   <li>Max attempts: 5</li>
 * </ul>
 */
final class RetryStrategy {

    private static final Logger log = LoggerFactory.getLogger(RetryStrategy.class);

    static final int MAX_ATTEMPTS = 5;
    static final long BASE_DELAY_MS = 100L;
    static final double MULTIPLIER = 2.0;
    static final double MAX_JITTER_FACTOR = 0.20;

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
        long delayMs = BASE_DELAY_MS;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                FetchResult result = request.call();
                lastResult = result;

                if (!shouldRetry(result.getStatusCode())) {
                    return result;
                }

                log.warn("attempt {}/{} returned HTTP {}; retrying",
                        attempt, MAX_ATTEMPTS, result.getStatusCode());
            } catch (IOException e) {
                lastNetworkError = e;
                log.warn("attempt {}/{} failed: {}; retrying",
                        attempt, MAX_ATTEMPTS, e.getMessage());
            } catch (Exception e) {
                throw new IOException("unexpected error during fetch", e);
            }

            if (attempt < MAX_ATTEMPTS) {
                long jitter = (long) (delayMs * random.nextDouble() * MAX_JITTER_FACTOR);
                long sleep = delayMs + jitter;
                try {
                    sleeper.sleep(sleep);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted during retry backoff", ie);
                }
                delayMs = (long) (delayMs * MULTIPLIER);
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
