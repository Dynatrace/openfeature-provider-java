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
import java.util.concurrent.atomic.AtomicBoolean;
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
    void setIntervalToLongerCadenceSlowsPolling() throws InterruptedException {
        CountDownLatch firstPoll = new CountDownLatch(1);
        AtomicInteger pollCount = new AtomicInteger();

        create(
                () -> {
                    pollCount.incrementAndGet();
                    firstPoll.countDown();
                    return new FetchResult(304, null, null, null);
                },
                body -> Collections.emptyList(),
                keys -> {},
                () -> {},
                reason -> {}
        ).start();

        assertTrue(firstPoll.await(2, TimeUnit.SECONDS));
        // switch to a very long interval so no more polls happen within the test window
        manager.setInterval(Duration.ofSeconds(60));
        int countAfterSwitch = pollCount.get();

        Thread.sleep(200); // longer than the old 50ms cadence, shorter than the new one
        // at most one poll may have been in flight when the interval changed; none after
        assertTrue(pollCount.get() <= countAfterSwitch + 1,
                "expected polling to slow down after setInterval");
    }

    @Test
    void setIntervalToShorterCadenceReAnchorsImmediately() throws InterruptedException {
        // start slow so no poll fires on its own, then switch to a fast cadence
        CountDownLatch polled = new CountDownLatch(2);
        manager = new PollingManager(
                () -> { polled.countDown(); return new FetchResult(304, null, null, null); },
                body -> Collections.emptyList(),
                keys -> {},
                () -> {},
                reason -> {},
                Duration.ofSeconds(60));
        manager.start();
        manager.setInterval(Duration.ofMillis(50));

        assertTrue(polled.await(2, TimeUnit.SECONDS),
                "switching to a short interval should re-anchor the cadence and poll promptly");
    }

    @Test
    @Timeout(10)
    void contendedPollTickIsSkippedAndEmitsNothing() throws InterruptedException {
        // KB 2.1.1: a tick that finds a fetch in flight is skipped. It must not cancel the
        // in-flight fetch, and must emit neither success nor failure; emitting success would
        // reset the staleness grace timer on every tick of a CDN outage.
        AtomicBoolean firstWasInterrupted = new AtomicBoolean(false);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger attempt = new AtomicInteger();

        // sampled from inside the in-flight fetch, so the assertions describe exactly the
        // window during which ticks were contending
        AtomicInteger contendedAttempts = new AtomicInteger(-1);
        AtomicInteger contendedSuccesses = new AtomicInteger(-1);
        AtomicInteger contendedFailures = new AtomicInteger(-1);
        CountDownLatch sampled = new CountDownLatch(1);

        create(
                () -> {
                    int n = attempt.incrementAndGet();
                    if (n == 1) {
                        try {
                            // 50ms cadence: roughly eight ticks contend during this window
                            Thread.sleep(400);
                        } catch (InterruptedException e) {
                            firstWasInterrupted.set(true);
                            throw e;
                        }
                        contendedAttempts.set(attempt.get());
                        contendedSuccesses.set(successes.get());
                        contendedFailures.set(failures.get());
                        sampled.countDown();
                    }
                    return new FetchResult(304, null, null, null);
                },
                body -> Collections.emptyList(),
                keys -> {},
                successes::incrementAndGet,
                reason -> failures.incrementAndGet()
        ).start();

        assertTrue(sampled.await(3, TimeUnit.SECONDS),
                "the overrunning poll should run to completion, not be cancelled");
        manager.stop();

        assertFalse(firstWasInterrupted.get(), "a contended tick must not interrupt the in-flight fetch");
        assertEquals(1, contendedAttempts.get(), "contended ticks must not start a second fetch");
        assertEquals(0, contendedSuccesses.get(), "skipped ticks must not emit success");
        assertEquals(0, contendedFailures.get(), "skipped ticks must not emit failure");
    }

    @Test
    void skippedFetchResultEmitsNeitherSuccessNorFailure() throws InterruptedException {
        // CdnFetcher returns skipped() when its own single-flight lock is contended
        CountDownLatch fetched = new CountDownLatch(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        create(
                () -> {
                    fetched.countDown();
                    return FetchResult.skipped();
                },
                body -> { fail("storeFlags should not be called on a skipped fetch"); return null; },
                keys -> {},
                successes::incrementAndGet,
                reason -> failures.incrementAndGet()
        ).start();

        assertTrue(fetched.await(2, TimeUnit.SECONDS));
        assertEquals(0, successes.get(), "a skipped fetch must not reset the grace timer");
        assertEquals(0, failures.get(), "a skipped fetch is not a failure");
    }

    @Test
    void sseRefetchFiresQuicklyWithLongInterval() throws InterruptedException {
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
        manager.triggerSseRefetch(null);

        assertTrue(triggered.await(2, TimeUnit.SECONDS), "SSE-triggered re-fetch should fire quickly");
    }

    @Test
    void sseRefetchDoesNotThrowWhenNotStarted() {
        manager = new PollingManager(
                () -> new FetchResult(304, null, null, null),
                body -> Collections.emptyList(),
                keys -> {},
                () -> {},
                reason -> {},
                Duration.ofMillis(50)
        );
        // not started — should not throw
        assertDoesNotThrow(() -> manager.triggerSseRefetch(1_000L));
    }

    @Test
    @Timeout(10)
    void contendedSseRefetchIsDeferredNotDropped() throws InterruptedException {
        // KB 2.1.1: an SSE re-fetch landing on a busy slot is deferred, then issued.
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch secondFetch = new CountDownLatch(2);

        manager = new PollingManager(
                () -> {
                    int n = fetches.incrementAndGet();
                    secondFetch.countDown();
                    if (n == 1) {
                        firstStarted.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        // no Last-Modified: nothing to compare against, so the deferral stands
                        return new FetchResult(304, null, null, null);
                    }
                    return new FetchResult(304, null, null, null);
                },
                body -> Collections.emptyList(),
                keys -> {},
                () -> {},
                reason -> {},
                Duration.ofSeconds(60)
        );
        manager.start();

        manager.triggerSseRefetch(2_000L);
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        // lands while the first is still running
        manager.triggerSseRefetch(3_000L);
        release.countDown();

        assertTrue(secondFetch.await(3, TimeUnit.SECONDS),
                "the deferred SSE re-fetch should be issued once the slot frees");
        assertEquals(2, fetches.get());
    }

    @Test
    @Timeout(10)
    void contendedSseRefetchesCoalesceIntoOne() throws InterruptedException {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();

        manager = new PollingManager(
                () -> {
                    int n = fetches.incrementAndGet();
                    if (n == 1) {
                        firstStarted.countDown();
                        release.await(5, TimeUnit.SECONDS);
                    }
                    return new FetchResult(304, null, null, null);
                },
                body -> Collections.emptyList(),
                keys -> {},
                () -> {},
                reason -> {},
                Duration.ofSeconds(60)
        );
        manager.start();

        manager.triggerSseRefetch(2_000L);
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        // five notifications while the slot is busy must collapse to a single re-fetch
        for (int i = 0; i < 5; i++) {
            manager.triggerSseRefetch(3_000L + i);
        }
        release.countDown();

        Thread.sleep(500);
        assertEquals(2, fetches.get(), "deferred SSE re-fetches should coalesce into one");
    }

    @Test
    @Timeout(10)
    void deferredSseRefetchIsDroppedWhenCompletedFetchIsAlreadyNewEnough() throws InterruptedException {
        // announced lastModified = 1700000000 (epoch seconds); the in-flight fetch returns a
        // config stamped at exactly that instant, so the deferred re-fetch has nothing to learn.
        String lastModifiedHeader = "Tue, 14 Nov 2023 22:13:20 GMT"; // == 1700000000
        assertEquals(1_700_000_000L, HttpDates.parseEpochSeconds(lastModifiedHeader));

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();

        manager = new PollingManager(
                () -> {
                    int n = fetches.incrementAndGet();
                    if (n == 1) {
                        firstStarted.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return new FetchResult(200, "{}", null, lastModifiedHeader);
                    }
                    return new FetchResult(304, null, null, null);
                },
                body -> Collections.emptyList(),
                keys -> {},
                () -> {},
                reason -> {},
                Duration.ofSeconds(60)
        );
        manager.start();

        manager.triggerSseRefetch(1_700_000_000L);
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        manager.triggerSseRefetch(1_700_000_000L);
        release.countDown();

        Thread.sleep(500);
        assertEquals(1, fetches.get(),
                "a deferred re-fetch should be dropped when the completed fetch is already that new");
    }

    @Test
    @Timeout(10)
    void deferredSseRefetchIsIssuedWhenCompletedFetchIsOlder() throws InterruptedException {
        String lastModifiedHeader = "Tue, 14 Nov 2023 22:13:20 GMT"; // == 1700000000

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();

        manager = new PollingManager(
                () -> {
                    int n = fetches.incrementAndGet();
                    if (n == 1) {
                        firstStarted.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return new FetchResult(200, "{}", null, lastModifiedHeader);
                    }
                    return new FetchResult(304, null, null, null);
                },
                body -> Collections.emptyList(),
                keys -> {},
                () -> {},
                reason -> {},
                Duration.ofSeconds(60)
        );
        manager.start();

        manager.triggerSseRefetch(1_700_000_000L);
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        // announces a config newer than what the in-flight fetch will return
        manager.triggerSseRefetch(1_700_000_060L);
        release.countDown();

        Thread.sleep(500);
        assertEquals(2, fetches.get(),
                "a deferred re-fetch announcing a newer config must still be issued");
    }

    @Test
    @Timeout(10)
    void coalescedSseRefetchRetainsTheGreatestAnnouncedLastModified() throws InterruptedException {
        // An out-of-order (older) notification arriving after a newer one must not replace it.
        // If the pending slot kept the latest arrival rather than the greatest value, the
        // completing fetch would look new enough and the pending re-fetch would be dropped,
        // hiding the newer config until the next poll tick.
        String completedAt = "Tue, 14 Nov 2023 22:13:20 GMT"; // == 1700000000
        assertEquals(1_700_000_000L, HttpDates.parseEpochSeconds(completedAt));

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();

        manager = new PollingManager(
                () -> {
                    int n = fetches.incrementAndGet();
                    if (n == 1) {
                        firstStarted.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return new FetchResult(200, "{}", null, completedAt);
                    }
                    return new FetchResult(304, null, null, null);
                },
                body -> Collections.emptyList(),
                keys -> {},
                () -> {},
                reason -> {},
                Duration.ofSeconds(60)
        );
        manager.start();

        manager.triggerSseRefetch(1_700_000_000L);
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        // newer notification first, then an out-of-order older one
        manager.triggerSseRefetch(1_700_000_060L);
        manager.triggerSseRefetch(1_700_000_000L);
        release.countDown();

        Thread.sleep(500);
        assertEquals(2, fetches.get(),
                "the coalesced re-fetch must keep the greatest announced Last-Modified, so a "
                        + "completion older than it is still not new enough to drop the re-fetch");
    }

    @Test
    @Timeout(10)
    void coalescedSseRefetchTreatsUnknownLastModifiedAsNotDroppable() throws InterruptedException {
        // A reconnect catch-up announces no lastModified. Unknown must win over any value,
        // since there is nothing to prove the completed fetch is new enough.
        String completedAt = "Tue, 14 Nov 2023 22:13:20 GMT"; // == 1700000000

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();

        manager = new PollingManager(
                () -> {
                    int n = fetches.incrementAndGet();
                    if (n == 1) {
                        firstStarted.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return new FetchResult(200, "{}", null, completedAt);
                    }
                    return new FetchResult(304, null, null, null);
                },
                body -> Collections.emptyList(),
                keys -> {},
                () -> {},
                reason -> {},
                Duration.ofSeconds(60)
        );
        manager.start();

        manager.triggerSseRefetch(1_700_000_000L);
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        manager.triggerSseRefetch(1_700_000_000L); // droppable on its own
        manager.triggerSseRefetch(null);           // unknown, must make the pending undroppable
        release.countDown();

        Thread.sleep(500);
        assertEquals(2, fetches.get(),
                "an unknown announced Last-Modified must keep the coalesced re-fetch issuable");
    }
}
