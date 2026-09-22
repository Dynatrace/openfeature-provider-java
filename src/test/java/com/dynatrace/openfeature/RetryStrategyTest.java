package com.dynatrace.openfeature;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryStrategyTest {

    private final List<Long> sleeps = new ArrayList<>();
    private final RetryStrategy.Sleeper recordingSleeper = sleeps::add;

    private RetryStrategy strategy() {
        return new RetryStrategy(new Random(42), recordingSleeper);
    }

    @Test
    void successOnFirstAttemptDoesNotRetry() throws IOException {
        FetchResult ok = new FetchResult(200, "{}", null, null);

        FetchResult result = strategy().execute(() -> ok);

        assertEquals(200, result.getStatusCode());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void retriesOn5xxUpToMaxAttempts() throws IOException {
        FetchResult serverError = new FetchResult(503, null, null, null);

        FetchResult result = strategy().execute(() -> serverError);

        assertEquals(503, result.getStatusCode());
        assertEquals(RetryStrategy.MAX_ATTEMPTS - 1, sleeps.size());
    }

    @Test
    void doesNotRetryOn4xx() throws IOException {
        FetchResult clientError = new FetchResult(400, null, null, null);

        FetchResult result = strategy().execute(() -> clientError);

        assertEquals(400, result.getStatusCode());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void doesNotRetryOn304() throws IOException {
        FetchResult notModified = new FetchResult(304, null, null, null);

        FetchResult result = strategy().execute(() -> notModified);

        assertEquals(304, result.getStatusCode());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void retriesOnIOException() {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(IOException.class, () -> strategy().execute(() -> {
            attempts.incrementAndGet();
            throw new IOException("connection refused");
        }));

        assertEquals(RetryStrategy.MAX_ATTEMPTS, attempts.get());
    }

    @Test
    void recoversAfterTransientFailureOnTheSingleRetry() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        FetchResult ok = new FetchResult(200, "{}", null, null);

        FetchResult result = strategy().execute(() -> {
            if (attempts.incrementAndGet() < 2) {
                return new FetchResult(503, null, null, null);
            }
            return ok;
        });

        assertEquals(200, result.getStatusCode());
        assertEquals(2, attempts.get());
        assertEquals(1, sleeps.size());
    }

    @Test
    void capsAtTwoAttemptsWithOneBackoff() throws IOException {
        // KB 2.4: one retry, not a loop; the fetch must stay short enough that it cannot
        // outlive the shortest poll interval (KB 2.1).
        assertEquals(2, RetryStrategy.MAX_ATTEMPTS);

        AtomicInteger attempts = new AtomicInteger();
        strategy().execute(() -> {
            attempts.incrementAndGet();
            return new FetchResult(500, null, null, null);
        });

        assertEquals(2, attempts.get());
        assertEquals(1, sleeps.size());
    }

    @Test
    void backoffIsFixedBaseDelayPlusJitter() throws IOException {
        FetchResult serverError = new FetchResult(500, null, null, null);

        strategy().execute(() -> serverError);

        assertEquals(1, sleeps.size());
        long delay = sleeps.get(0);
        assertTrue(delay >= RetryStrategy.BASE_DELAY_MS,
                "delay " + delay + " should be at least the base delay");
        assertTrue(delay <= RetryStrategy.BASE_DELAY_MS * (1 + RetryStrategy.MAX_JITTER_FACTOR),
                "delay " + delay + " should not exceed base delay + 20% jitter");
    }

    @Test
    void interruptedDuringBackoffThrowsIOException() {
        RetryStrategy strategy = new RetryStrategy(new Random(42), ms -> {
            throw new InterruptedException("interrupted");
        });

        assertThrows(IOException.class, () ->
                strategy.execute(() -> new FetchResult(500, null, null, null)));

        assertTrue(Thread.currentThread().isInterrupted());
        // clear interrupt flag for test framework
        Thread.interrupted();
    }

    @Test
    void returnsLastResultWhenAllAttemptsRetryable() throws IOException {
        AtomicInteger attempts = new AtomicInteger();

        FetchResult result = strategy().execute(() ->
                new FetchResult(500 + attempts.incrementAndGet(), null, null, null));

        // should return the last 5xx result, not throw
        assertTrue(result.getStatusCode() >= 500);
    }

    @Test
    void shouldRetryOnlyFor5xx() {
        assertFalse(RetryStrategy.shouldRetry(200));
        assertFalse(RetryStrategy.shouldRetry(304));
        assertFalse(RetryStrategy.shouldRetry(400));
        assertFalse(RetryStrategy.shouldRetry(404));
        assertFalse(RetryStrategy.shouldRetry(429));
        assertTrue(RetryStrategy.shouldRetry(500));
        assertTrue(RetryStrategy.shouldRetry(502));
        assertTrue(RetryStrategy.shouldRetry(503));
        assertTrue(RetryStrategy.shouldRetry(599));
        assertFalse(RetryStrategy.shouldRetry(600));
    }
}
