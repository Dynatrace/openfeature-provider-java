package com.dynatrace.openfeature;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
import java.util.function.Supplier;

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

    private PollingManager createWithSupplier(
            Callable<FetchResult> fetchFlags,
            Runnable emitSuccess,
            Consumer<String> emitFailure,
            Supplier<Duration> intervalSupplier) {
        manager = new PollingManager(fetchFlags, body -> Collections.emptyList(), keys -> {}, emitSuccess, emitFailure, intervalSupplier);
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
    void dynamicIntervalIsRespected() throws InterruptedException {
        // start with a short interval, switch to a long one — verify polling slows down
        AtomicReference<Duration> currentInterval = new AtomicReference<>(Duration.ofMillis(50));
        CountDownLatch firstPoll = new CountDownLatch(1);
        AtomicInteger pollCount = new AtomicInteger();

        createWithSupplier(
                () -> {
                    pollCount.incrementAndGet();
                    firstPoll.countDown();
                    return new FetchResult(304, null, null, null);
                },
                () -> {},
                reason -> {},
                currentInterval::get
        ).start();

        assertTrue(firstPoll.await(2, TimeUnit.SECONDS));
        // switch to very long interval so no more polls happen
        currentInterval.set(Duration.ofSeconds(60));
        int countAfterSwitch = pollCount.get();

        Thread.sleep(200); // wait longer than old short interval but less than new long one
        // at most one poll in flight when interval changed; after that no new ones
        assertTrue(pollCount.get() <= countAfterSwitch + 1,
                "expected polling to slow down after interval change");
    }

    @Test
    void triggerImmediateFetchFiresQuicklyWithLongInterval() throws InterruptedException {
        // 60s interval means no poll would fire within the test window without a trigger
        CountDownLatch triggered = new CountDownLatch(1);

        manager = new PollingManager(
                () -> {
                    triggered.countDown();
                    return new FetchResult(304, null, null, null);
                },
                body -> Collections.emptyList(),
                keys -> {},
                () -> {},
                reason -> {},
                Duration.ofSeconds(60)
        );
        manager.start();
        manager.triggerImmediateFetch();

        // if triggerImmediateFetch works, the poll fires immediately despite the 60s interval
        assertTrue(triggered.await(2, TimeUnit.SECONDS), "triggered poll should fire quickly");
    }

    @Test
    void triggerImmediateFetchDoesNotThrowWhenNotStarted() {
        manager = new PollingManager(
                () -> new FetchResult(304, null, null, null),
                body -> Collections.emptyList(),
                keys -> {},
                () -> {},
                reason -> {},
                Duration.ofMillis(50)
        );
        // not started — should not throw
        assertDoesNotThrow(() -> manager.triggerImmediateFetch());
    }
}
