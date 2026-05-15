package com.dynatrace.openfeature;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PollingManagerTest {

    private PollingManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    private PollingManager create(
            Callable<FetchResult> fetchFlags,
            Function<String, List<String>> storeFlags,
            Consumer<List<String>> emitChange,
            Runnable emitSuccess,
            Consumer<String> emitFailure) {
        manager = new PollingManager(fetchFlags, storeFlags, emitChange, emitSuccess, emitFailure, Duration.ofMillis(50));
        return manager;
    }

    @Test
    void successfulPollEmitsSuccess() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger storeCount = new AtomicInteger();

        create(
                () -> new FetchResult(200, "{}", null, null),
                body -> { storeCount.incrementAndGet(); return Collections.emptyList(); },
                keys -> {},
                latch::countDown,
                reason -> fail("unexpected failure: " + reason)
        ).start();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, storeCount.get());
    }

    @Test
    void notModifiedEmitsSuccessWithoutStore() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        create(
                () -> new FetchResult(304, null, null, null),
                body -> { fail("storeFlags should not be called on 304"); return null; },
                keys -> {},
                latch::countDown,
                reason -> fail("unexpected failure: " + reason)
        ).start();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    void httpErrorEmitsFailure() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> failureReason = new AtomicReference<>();

        create(
                () -> new FetchResult(503, null, null, null),
                body -> { fail("storeFlags should not be called on error"); return null; },
                keys -> {},
                () -> fail("emitSuccess should not be called on error"),
                reason -> { failureReason.set(reason); latch.countDown(); }
        ).start();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("HTTP 503", failureReason.get());
    }

    @Test
    void emptyBodyEmitsFailure() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        create(
                () -> new FetchResult(200, "", null, null),
                body -> { fail("storeFlags should not be called on empty body"); return null; },
                keys -> {},
                () -> fail("emitSuccess should not be called on empty body"),
                reason -> latch.countDown()
        ).start();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    void changedKeysEmitsChange() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> capturedKeys = new ArrayList<>();

        create(
                () -> new FetchResult(200, "{}", null, null),
                body -> Arrays.asList("flag-a", "flag-b"),
                keys -> { capturedKeys.addAll(keys); latch.countDown(); },
                () -> {},
                reason -> fail("unexpected failure: " + reason)
        ).start();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("flag-a", "flag-b"), capturedKeys);
    }

    @Test
    void noChangedKeysDoesNotEmitChange() throws InterruptedException {
        CountDownLatch successLatch = new CountDownLatch(1);
        AtomicInteger changedCount = new AtomicInteger();

        create(
                () -> new FetchResult(200, "{}", null, null),
                body -> Collections.emptyList(),
                keys -> changedCount.incrementAndGet(),
                successLatch::countDown,
                reason -> fail("unexpected failure: " + reason)
        ).start();

        assertTrue(successLatch.await(2, TimeUnit.SECONDS));
        assertEquals(0, changedCount.get());
    }

    @Test
    void fetchExceptionEmitsFailure() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> failureReason = new AtomicReference<>();

        create(
                () -> { throw new RuntimeException("network error"); },
                body -> null,
                keys -> {},
                () -> fail("emitSuccess should not be called on exception"),
                reason -> { failureReason.set(reason); latch.countDown(); }
        ).start();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("network error", failureReason.get());
    }

    @Test
    void stopPreventsFurtherPolling() throws InterruptedException {
        AtomicInteger pollCount = new AtomicInteger();
        CountDownLatch firstPoll = new CountDownLatch(1);

        create(
                () -> {
                    pollCount.incrementAndGet();
                    firstPoll.countDown();
                    return new FetchResult(304, null, null, null);
                },
                body -> null,
                keys -> {},
                () -> {},
                reason -> {}
        ).start();

        assertTrue(firstPoll.await(2, TimeUnit.SECONDS));
        manager.stop();

        int countAfterStop = pollCount.get();
        Thread.sleep(200); // wait to verify no more polls
        assertEquals(countAfterStop, pollCount.get());
    }

    @Test
    void rateLimitedDoesNotEmitFailure() throws InterruptedException {
        // 429 followed by a 304 to confirm we resume normally
        AtomicInteger pollCount = new AtomicInteger();
        CountDownLatch successLatch = new CountDownLatch(1);

        create(
                () -> {
                    if (pollCount.incrementAndGet() == 1) {
                        return new FetchResult(429, null, null, null);
                    }
                    return new FetchResult(304, null, null, null);
                },
                body -> { fail("storeFlags should not be called on 429"); return null; },
                keys -> {},
                successLatch::countDown,
                reason -> fail("429 should not emit failure: " + reason)
        ).start();

        assertTrue(successLatch.await(2, TimeUnit.SECONDS));
    }

    @Test
    void pollsRepeatedly() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);

        create(
                () -> new FetchResult(304, null, null, null),
                body -> null,
                keys -> {},
                latch::countDown,
                reason -> {}
        ).start();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    void pollFiresOnFixedSchedule() throws InterruptedException {
        // fetchFlags blocks for longer than the poll interval; verify the ticker
        // doesn't wait for the previous fetch to finish before firing the next one.
        CountDownLatch startedLatch = new CountDownLatch(3);

        create(
                () -> {
                    startedLatch.countDown();
                    Thread.sleep(500); // much longer than the 50ms poll interval
                    return new FetchResult(304, null, null, null);
                },
                body -> null,
                keys -> {},
                () -> {},
                reason -> {}
        ).start();

        // 3 fetches should have started well within 300ms (3 × 50ms ticks + slack)
        assertTrue(startedLatch.await(1, TimeUnit.SECONDS),
                "polling should fire on a fixed schedule regardless of fetch duration");
    }

    @Test
    void hangingPollIsCancelledOnNextTick() throws InterruptedException {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicReference<Boolean> firstInterrupted = new AtomicReference<>(false);

        AtomicInteger callCount = new AtomicInteger();
        create(
                () -> {
                    int call = callCount.incrementAndGet();
                    if (call == 1) {
                        firstStarted.countDown();
                        try {
                            Thread.sleep(10_000); // hang until interrupted
                        } catch (InterruptedException e) {
                            firstInterrupted.set(true);
                            Thread.currentThread().interrupt();
                            throw e;
                        }
                    } else {
                        secondStarted.countDown();
                    }
                    return new FetchResult(304, null, null, null);
                },
                body -> null,
                keys -> {},
                () -> {},
                reason -> {}
        ).start();

        assertTrue(firstStarted.await(1, TimeUnit.SECONDS), "first poll should start");
        assertTrue(secondStarted.await(1, TimeUnit.SECONDS), "second poll should start after cancelling the first");
        assertTrue(firstInterrupted.get(), "first poll should have been interrupted");
    }

    @Test
    void cancelledPollDoesNotEmitFailure() throws InterruptedException {
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger failureCount = new AtomicInteger();
        AtomicInteger callCount = new AtomicInteger();

        create(
                () -> {
                    int call = callCount.incrementAndGet();
                    if (call == 1) {
                        Thread.sleep(10_000); // hang until interrupted by next tick
                    } else {
                        secondStarted.countDown();
                    }
                    return new FetchResult(304, null, null, null);
                },
                body -> null,
                keys -> {},
                () -> {},
                reason -> failureCount.incrementAndGet()
        ).start();

        assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
        assertEquals(0, failureCount.get(), "cancellation of an in-flight poll must not emit a failure event");
    }

    @Test
    void stopInterruptsInFlightPoll() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);

        create(
                () -> {
                    started.countDown();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    return new FetchResult(304, null, null, null);
                },
                body -> null,
                keys -> {},
                () -> {},
                reason -> {}
        ).start();

        assertTrue(started.await(1, TimeUnit.SECONDS));
        long before = System.currentTimeMillis();
        manager.stop();
        long elapsed = System.currentTimeMillis() - before;

        assertTrue(elapsed < 500, "stop() should return promptly, took " + elapsed + "ms");
        assertTrue(interrupted.get(), "in-flight poll should have been interrupted by stop()");
    }
}
