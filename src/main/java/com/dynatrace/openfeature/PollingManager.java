package com.dynatrace.openfeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * <p>A ticker fires every {@code interval}, anchored to start rather than to how long a poll
 * takes, so retry/backoff inside a poll never stretches the cadence, and hands the actual work to
 * a single worker thread.
 *
 * <p><b>Single-flight (KB section 2.1.1).</b> At most one fetch runs at a time, and a contended
 * trigger is never cancelled-and-restarted:
 * <ul>
 *   <li>A <b>poll tick</b> is <b>skipped</b>, emitting neither success nor failure, so it does
 *       not reset the staleness grace timer. Reporting it as a success would hold the provider
 *       in {@code READY} for a whole CDN outage, hiding what grace exists to surface.</li>
 *   <li>An <b>SSE-triggered re-fetch</b> is <b>deferred and coalesced</b> into a single pending
 *       re-fetch, issued when the slot frees, and dropped only if the completed fetch already
 *       returned a config at least as new as the announced {@code lastModified}. Dropping it
 *       outright would hide a config change for up to a poll interval (10 minutes on SSE).</li>
 * </ul>
 *
 * <p>The interval is event-driven via {@link #setInterval(Duration)}: the provider switches between
 * the SSE-connected safety-net cadence and the faster SSE-disconnected cadence on SSE
 * connect/disconnect, rather than re-evaluating an interval every cycle.
 */
final class PollingManager {

    private static final Logger log = LoggerFactory.getLogger(PollingManager.class);

    /** Stands in for an SSE announcement that carried no {@code lastModified}. */
    private static final long LAST_MODIFIED_UNKNOWN = Long.MAX_VALUE;

    private final Callable<FetchResult> fetchFlags;
    private final Function<String, List<String>> storeFlags;
    private final Consumer<List<String>> emitChange;
    private final Runnable emitSuccess;
    private final Consumer<String> emitFailure;

    private volatile Duration interval;

    private ScheduledExecutorService ticker;
    private ExecutorService worker;
    private ScheduledFuture<?> tickerFuture;

    /**
     * True from the moment a fetch is submitted until {@link #onFetchComplete} has settled it.
     * Tracked here rather than read off the submitted {@code Future}, whose {@code isDone()} only
     * flips after the task returns: a trigger landing in that gap would defer behind a fetch that
     * has already settled, stranding the announcement until the next tick.
     */
    private boolean fetchInFlight;

    /**
     * Announced epoch seconds of a deferred SSE re-fetch, or null when none is pending.
     * {@link #LAST_MODIFIED_UNKNOWN} means the announcement carried no {@code lastModified}, so
     * the re-fetch can never be dropped.
     */
    private Long pendingSseRefetchAt;

    /**
     * Announcement the running fetch was issued for, or null when it is a plain poll tick. Kept so
     * a re-fetch that turns out to make no request can put its announcement back.
     */
    private Long inFlightSseRefetchAt;

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
     * Starts polling. Must be called after the initial fetch/store completes (KB section 3.1
     * step 4), so initialization never contends with the first tick.
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
     * Requests an out-of-band fetch on behalf of SSE. Runs immediately if the fetch slot is free,
     * otherwise it is deferred and coalesced. Safe to call from any thread; does not disturb the
     * ticker cadence.
     *
     * @param announcedLastModified epoch seconds from the SSE message, or null when unknown (a
     *                              reconnect catch-up). A deferred re-fetch is dropped only when
     *                              the completed fetch already returned a config at least this new.
     */
    synchronized void requestOutOfBandFetch(Long announcedLastModified) {
        if (worker == null || worker.isShutdown()) {
            return;
        }
        // Unknown stands in as the greatest value, so it is never coalesced away or dropped.
        long announced = announcedLastModified == null ? LAST_MODIFIED_UNKNOWN : announcedLastModified;
        if (fetchInFlight) {
            pendingSseRefetchAt =
                    pendingSseRefetchAt == null ? announced : Math.max(pendingSseRefetchAt, announced);
            log.debug("fetch in flight; deferring SSE re-fetch (lastModified={})", announcedLastModified);
            return;
        }
        submit(announced);
    }

    /** Stops polling and shuts down the ticker and worker, interrupting any in-flight fetch. */
    synchronized void stop() {
        pendingSseRefetchAt = null;
        inFlightSseRefetchAt = null;
        fetchInFlight = false;
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
        tickerFuture = ticker.scheduleAtFixedRate(this::onTick, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /** Poll tick. Skipped if a fetch is in flight: that fetch already does what this tick wanted. */
    private synchronized void onTick() {
        if (worker == null || worker.isShutdown()) {
            return;
        }
        if (fetchInFlight) {
            log.debug("poll tick skipped: a fetch is already in flight");
            return;
        }
        submit(null);
    }

    /**
     * Caller must hold the monitor.
     *
     * @param sseRefetchAt announcement this fetch is issued for, or null for a plain poll tick
     */
    private void submit(Long sseRefetchAt) {
        inFlightSseRefetchAt = sseRefetchAt;
        fetchInFlight = true;
        try {
            worker.submit(this::runFetch);
        } catch (RejectedExecutionException e) {
            // worker shut down between the guard and submit; ignore
            inFlightSseRefetchAt = null;
            fetchInFlight = false;
        }
    }

    private void runFetch() {
        FetchResult result = null;
        try {
            result = fetchFlags.call();
            handleResult(result);
        } catch (Exception e) {
            // Only shutdown interrupts this thread now that contended ticks are skipped rather
            // than cancelled, so an interrupt means "stopping", not a poll failure.
            if (isInterrupted(e)) {
                log.debug("poll interrupted (shutting down)");
            } else {
                log.warn("poll failed", e);
                emitFailure.accept(e.getMessage());
            }
        } finally {
            onFetchComplete(result);
        }
    }

    /**
     * Settles the SSE re-fetch bookkeeping now that a fetch has finished.
     *
     * @param completed result of the fetch that just finished, or null if it threw
     */
    private synchronized void onFetchComplete(FetchResult completed) {
        Long issuedFor = inFlightSseRefetchAt;
        inFlightSseRefetchAt = null;
        // Frees the slot before anything below can re-submit into it.
        fetchInFlight = false;

        if (completed != null && completed.isSkipped()) {
            // No request was made, so a re-fetch we issued is still unsatisfied; put its
            // announcement back rather than dropping it. Resubmitting here would just be skipped
            // again (whatever blocked it, a Retry-After window or the fetch lock, still holds) and
            // would spin, so the next fetch that actually reaches the CDN issues or drops it.
            if (issuedFor != null) {
                pendingSseRefetchAt = pendingSseRefetchAt == null
                        ? issuedFor
                        : Math.max(pendingSseRefetchAt, issuedFor);
                log.debug("SSE re-fetch made no request; keeping it pending");
            }
            return;
        }
        issuePendingSseRefetch(completed);
    }

    /**
     * Issues the deferred SSE re-fetch, if any, now that the slot is free. Runs on the worker
     * thread, so the re-fetch it submits queues behind the current task rather than overlapping it.
     *
     * @param completed result of the fetch that just finished, which made a request
     */
    private synchronized void issuePendingSseRefetch(FetchResult completed) {
        if (pendingSseRefetchAt == null || worker == null || worker.isShutdown()) {
            return;
        }
        long announced = pendingSseRefetchAt;
        pendingSseRefetchAt = null;

        Long observed = completed == null ? null : HttpDates.parseEpochSeconds(completed.getLastModified());
        if (announced != LAST_MODIFIED_UNKNOWN && observed != null && observed >= announced) {
            log.debug("dropping deferred SSE re-fetch: completed fetch already returned "
                    + "Last-Modified {} >= announced {}", observed, announced);
            return;
        }
        log.debug("issuing deferred SSE re-fetch");
        submit(announced);
    }

    private void handleResult(FetchResult result) {
        if (result.isSkipped()) {
            log.debug("fetch skipped: no request was made");
            return;
        }

        if (result.isNotModified()) {
            log.debug("flags not modified");
            emitSuccess.run();
            return;
        }

        // 429: rate limited; still serving cached values (spec 2.3)
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
