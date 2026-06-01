package com.dynatrace.openfeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Manages periodic polling of flag configurations with a dynamic interval.
 *
 * <p>Uses a self-rescheduling single-shot schedule so the interval can change at runtime
 * (e.g. switching between 10s when SSE is disconnected and 10m when SSE is connected).
 * Call {@link #triggerImmediateFetch()} to request an out-of-band fetch (e.g. on SSE notification).
 */
final class PollingManager {

    private static final Logger log = LoggerFactory.getLogger(PollingManager.class);

    private final Callable<FetchResult> fetchFlags;
    private final Function<String, List<String>> storeFlags;
    private final Consumer<List<String>> emitChange;
    private final Runnable emitSuccess;
    private final Consumer<String> emitFailure;
    private final Supplier<Duration> intervalSupplier;

    private ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> pollFuture;

    /**
     * @param fetchFlags       called to fetch flag configuration JSON from the CDN
     * @param storeFlags       called to store the fetched JSON; returns changed flag keys
     * @param emitChange       called with changed flag keys after a successful store
     * @param emitSuccess      called after any successful poll (including 304 not-modified)
     * @param emitFailure      called with a reason string when a poll fails
     * @param intervalSupplier supplies the delay before each subsequent poll; consulted after every cycle
     */
    PollingManager(
            Callable<FetchResult> fetchFlags,
            Function<String, List<String>> storeFlags,
            Consumer<List<String>> emitChange,
            Runnable emitSuccess,
            Consumer<String> emitFailure,
            Supplier<Duration> intervalSupplier) {
        this.fetchFlags = fetchFlags;
        this.storeFlags = storeFlags;
        this.emitChange = emitChange;
        this.emitSuccess = emitSuccess;
        this.emitFailure = emitFailure;
        this.intervalSupplier = intervalSupplier;
    }

    /** Convenience constructor for a fixed polling interval (used in tests). */
    PollingManager(
            Callable<FetchResult> fetchFlags,
            Function<String, List<String>> storeFlags,
            Consumer<List<String>> emitChange,
            Runnable emitSuccess,
            Consumer<String> emitFailure,
            Duration fixedInterval) {
        this(fetchFlags, storeFlags, emitChange, emitSuccess, emitFailure, () -> fixedInterval);
    }

    /**
     * Starts polling. Must be called after the initial fetch/store succeeds.
     * The first poll is scheduled after the first interval returned by the supplier.
     */
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dt-provider-poller");
            t.setDaemon(true);
            return t;
        });
        schedulePoll(intervalSupplier.get().toMillis());
    }

    /**
     * Cancels any pending poll and schedules an immediate fetch, followed by
     * resumption of the normal poll cadence. Safe to call from any thread.
     *
     * <p>If a poll is currently executing, the in-progress poll cannot be cancelled;
     * the immediate fetch is queued to run right after. The {@link CdnFetcher}'s
     * {@code tryLock} ensures overlapping requests are dropped rather than queued.
     */
    void triggerImmediateFetch() {
        ScheduledFuture<?> f = pollFuture;
        if (f != null) f.cancel(false);
        schedulePoll(0);
    }

    /**
     * Stops polling and shuts down the scheduler.
     * Uses shutdownNow() to cancel pending tasks that the self-rescheduling loop may have submitted.
     */
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        ScheduledFuture<?> f = pollFuture;
        if (f != null) f.cancel(false);
    }

    private void schedulePoll(long delayMs) {
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }
        pollFuture = scheduler.schedule(() -> {
            poll();
            schedulePoll(intervalSupplier.get().toMillis());
        }, delayMs, TimeUnit.MILLISECONDS);
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
                log.debug("poll cancelled by next tick");
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
}
