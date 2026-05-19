package com.dynatrace.openfeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Manages periodic polling of flag configurations.
 *
 * <p>Polling fires on a fixed schedule anchored to when each request is initiated, not when
 * the previous request completed. If a previous fetch is still in its retry/backoff cycle when
 * the next interval elapses, that fetch is cancelled and a fresh one begins.
 *
 * <p>Two executors are used: a single-threaded ticker that fires every {@code pollInterval} ms,
 * and a single-threaded worker that runs the actual fetch+retry work. The ticker is never blocked
 * by slow fetches.
 */
final class PollingManager {

    private static final Logger log = LoggerFactory.getLogger(PollingManager.class);

    private final Callable<FetchResult> fetchFlags;
    private final Function<String, List<String>> storeFlags;
    private final Consumer<List<String>> emitChange;
    private final Runnable emitSuccess;
    private final Consumer<String> emitFailure;
    private final Duration pollInterval;

    private ScheduledExecutorService ticker;
    private ExecutorService worker;
    private final AtomicReference<Future<?>> currentPoll = new AtomicReference<>();

    /**
     * @param fetchFlags   called to fetch flag configuration JSON from the CDN
     * @param storeFlags   called to store the fetched JSON into the flag engine; returns changed flag keys
     * @param emitChange   called with the list of changed flag keys after a successful store
     * @param emitSuccess  called after any successful poll (including 304 not-modified)
     * @param emitFailure  called with a reason string when a poll fails
     * @param pollInterval interval between poll initiations (measured from start-of-request, not end)
     */
    PollingManager(
            Callable<FetchResult> fetchFlags,
            Function<String, List<String>> storeFlags,
            Consumer<List<String>> emitChange,
            Runnable emitSuccess,
            Consumer<String> emitFailure,
            Duration pollInterval) {
        this.fetchFlags = fetchFlags;
        this.storeFlags = storeFlags;
        this.emitChange = emitChange;
        this.emitSuccess = emitSuccess;
        this.emitFailure = emitFailure;
        this.pollInterval = pollInterval;
    }

    /**
     * Starts polling. Must be called after initial fetch/store succeeds.
     */
    void start() {
        worker = Executors.newSingleThreadExecutor(daemonThread("dt-provider-poller"));
        ticker = Executors.newSingleThreadScheduledExecutor(daemonThread("dt-provider-ticker"));
        ticker.scheduleAtFixedRate(
                this::tick,
                pollInterval.toMillis(),
                pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * Stops polling and shuts down both executors. Interrupts any in-flight retry cycle.
     */
    void stop() {
        if (ticker != null) {
            ticker.shutdownNow();
        }
        Future<?> poll = currentPoll.getAndSet(null);
        if (poll != null) {
            poll.cancel(true);
        }
        if (worker != null) {
            worker.shutdownNow();
        }
    }

    private void tick() {
        Future<?> prev = currentPoll.getAndSet(null);
        if (prev != null && !prev.isDone()) {
            log.debug("cancelling in-flight poll; starting fresh");
            prev.cancel(true);
        }
        currentPoll.set(worker.submit(this::poll));
    }

    private void poll() {
        try {
            FetchResult result = fetchFlags.call();

            // cancel(true) may arrive after fetchFlags returns but before we apply the result;
            // discard rather than emit stale events into the provider state machine
            if (Thread.currentThread().isInterrupted()) {
                log.debug("poll cancelled after fetch returned; discarding result");
                return;
            }

            if (result.isNotModified()) {
                log.debug("flags not modified");
                emitSuccess.run();
                return;
            }

            // 429: rate limited, but we're still serving cached values (KB 2.3)
            if (result.isRateLimited()) {
                log.debug("rate limited; serving cached values");
                return;
            }

            if (!result.isSuccess()) {
                emitFailure.accept("HTTP " + result.getStatusCode());
                return;
            }

            String body = result.getBody();
            if (body == null || body.isEmpty()) {
                emitFailure.accept("empty response body");
                return;
            }

            List<String> changedKeys = storeFlags.apply(body);
            emitSuccess.run();

            if (changedKeys != null && !changedKeys.isEmpty()) {
                log.info("flag configuration changed: {}", changedKeys);
                emitChange.accept(changedKeys);
            }
        } catch (Exception e) {
            if (isInterrupted(e)) {
                log.debug("poll cancelled by next tick");
                return;
            }
            log.warn("poll failed", e);
            emitFailure.accept(e.getMessage());
        }
    }

    private static ThreadFactory daemonThread(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private static boolean isInterrupted(Exception e) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof InterruptedException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
