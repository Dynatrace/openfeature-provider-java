package com.dynatrace.openfeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Manages periodic polling of flag configurations at a fixed, start-anchored cadence.
 *
 * <p>A ticker fires every {@code interval} — anchored to start, not to how long a poll takes, so
 * retry/backoff inside a poll never stretches the cadence — and hands the actual work to a single
 * worker thread. The ticker never blocks: if a previous poll is still running when the next tick
 * fires, that poll is cancelled and a fresh one started.
 *
 * <p>The interval is event-driven via {@link #setInterval(Duration)}: the provider switches between
 * the SSE-connected safety-net cadence and the faster SSE-disconnected cadence on SSE
 * connect/disconnect, rather than re-evaluating an interval every cycle.
 *
 * <p>Call {@link #triggerImmediateFetch()} to request an out-of-band poll (e.g. on an SSE
 * notification, or right after an SSE disconnect) without disturbing the ticker cadence.
 */
final class PollingManager {

    private static final Logger log = LoggerFactory.getLogger(PollingManager.class);

    private final Callable<FetchResult> fetchFlags;
    private final Function<String, List<String>> storeFlags;
    private final Consumer<List<String>> emitChange;
    private final Runnable emitSuccess;
    private final Consumer<String> emitFailure;

    private volatile Duration interval;

    private ScheduledExecutorService ticker;
    private ExecutorService worker;
    private volatile ScheduledFuture<?> tickerFuture;
    private volatile Future<?> pollInFlight;

    /**
     * @param fetchFlags      called to fetch flag configuration JSON from the CDN
     * @param storeFlags      called to store the fetched JSON; returns changed flag keys
     * @param emitChange      called with changed flag keys after a successful store
     * @param emitSuccess     called after any successful poll (including 304 not-modified)
     * @param emitFailure     called with a reason string when a poll fails
     * @param initialInterval the starting poll cadence; change at runtime via {@link #setInterval}
     */
    PollingManager(
            Callable<FetchResult> fetchFlags,
            Function<String, List<String>> storeFlags,
            Consumer<List<String>> emitChange,
            Runnable emitSuccess,
            Consumer<String> emitFailure,
            Duration initialInterval) {
        this.fetchFlags = fetchFlags;
        this.storeFlags = storeFlags;
        this.emitChange = emitChange;
        this.emitSuccess = emitSuccess;
        this.emitFailure = emitFailure;
        this.interval = initialInterval;
    }

    /**
     * Starts polling. Must be called after the initial fetch/store succeeds.
     * The first poll fires one interval after start.
     */
    synchronized void start() {
        ticker = Executors.newSingleThreadScheduledExecutor(namedDaemon("dt-provider-poll-ticker"));
        worker = Executors.newSingleThreadExecutor(namedDaemon("dt-provider-poll-worker"));
        scheduleTicker();
    }

    /**
     * Changes the poll cadence and re-anchors the schedule to now. This is a rare, event-driven
     * call (SSE connect/disconnect), so there is no per-cycle reschedule churn. No-op if unchanged.
     */
    synchronized void setInterval(Duration newInterval) {
        if (newInterval.equals(interval)) {
            return;
        }
        interval = newInterval;
        scheduleTicker();
    }

    /**
     * Cancels any in-flight poll and runs one immediately on the worker. Safe to call from any
     * thread; does not disturb the ticker cadence. The {@link CdnFetcher}'s {@code tryLock} drops
     * overlapping fetches, so a redundant trigger costs at most one conditional GET.
     */
    void triggerImmediateFetch() {
        runPoll();
    }

    /** Stops polling and shuts down the ticker and worker, interrupting any in-flight poll. */
    synchronized void stop() {
        if (ticker != null) {
            ticker.shutdownNow();
        }
        if (worker != null) {
            worker.shutdownNow();
        }
    }

    private synchronized void scheduleTicker() {
        if (ticker == null || ticker.isShutdown()) {
            return;
        }
        ScheduledFuture<?> previous = tickerFuture;
        if (previous != null) {
            previous.cancel(false);
        }
        long periodMs = interval.toMillis();
        // Fixed-rate: ticks are anchored to start and never drift with poll duration. The tick body
        // only hands work to the worker, so the ticker thread itself never blocks.
        tickerFuture = ticker.scheduleAtFixedRate(this::runPoll, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Submits a poll to the worker, cancelling any still-running poll first so a slow or hung poll
     * (one that overran the cadence) is abandoned rather than allowed to delay the next tick.
     */
    private synchronized void runPoll() {
        if (worker == null || worker.isShutdown()) {
            return;
        }
        Future<?> previous = pollInFlight;
        if (previous != null && !previous.isDone()) {
            log.debug("previous poll still running; cancelling and starting fresh");
            previous.cancel(true);
        }
        try {
            pollInFlight = worker.submit(this::poll);
        } catch (RejectedExecutionException e) {
            // worker shut down between the guard and submit; ignore
        }
    }

    private void poll() {
        try {
            FetchResult result = fetchFlags.call();

            // cancel(true) may arrive after fetchFlags returns but before we apply the result;
            // discard rather than emit stale events into the provider state machine.
            // Thread.interrupted() clears the flag so the next poll on this reused thread is unaffected.
            if (Thread.interrupted()) {
                log.debug("poll cancelled after fetch returned; discarding result");
                return;
            }

            if (result.isNotModified()) {
                log.debug("flags not modified");
                emitSuccess.run();
                return;
            }

            // 429: rate limited; still serving cached values (spec §2.3)
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
                log.debug("poll cancelled");
                return;
            }
            log.warn("poll failed", e);
            emitFailure.accept(e.getMessage());
        }
    }

    private static boolean isInterrupted(Exception e) {
        // Thread.interrupted() clears the flag so the reused worker thread starts clean next poll
        if (Thread.interrupted()) {
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

    private static ThreadFactory namedDaemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
