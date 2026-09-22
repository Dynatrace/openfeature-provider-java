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
 * worker thread. The ticker never blocks.
 *
 * <p><b>Single-flight (KB section 2.1.1).</b> At most one fetch runs at a time, and a contended
 * trigger is never cancelled-and-restarted:
 * <ul>
 *   <li>A <b>poll tick</b> that finds a fetch in flight is <b>skipped</b>. It emits neither
 *       success nor failure, so it does not reset the staleness grace timer. Reporting it as a
 *       success would hold the provider in {@code READY} for the whole duration of a CDN outage,
 *       hiding exactly the condition the grace period exists to surface.</li>
 *   <li>An <b>SSE-triggered re-fetch</b> that finds a fetch in flight is <b>deferred and
 *       coalesced</b> into a single pending re-fetch, issued when the slot frees. It is dropped
 *       only if the fetch that just completed already returned a config at least as new as the
 *       SSE-announced {@code lastModified}. Dropping outright would hide a config change for up
 *       to a full poll interval — 10 minutes while SSE is connected.</li>
 * </ul>
 *
 * <p>The poll arithmetic (see {@link CdnFetcher#REQUEST_TIMEOUT} and
 * {@link RetryStrategy#MAX_ATTEMPTS}) already rules out poll-versus-poll overlap; the skip path
 * covers init and SSE contention.
 *
 * <p>The interval is event-driven via {@link #setInterval(Duration)}: the provider switches between
 * the SSE-connected safety-net cadence and the faster SSE-disconnected cadence on SSE
 * connect/disconnect, rather than re-evaluating an interval every cycle.
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
    private ScheduledFuture<?> tickerFuture;
    private Future<?> fetchInFlight;

    /** True while an SSE-triggered re-fetch is waiting for the in-flight fetch to finish. */
    private boolean pendingSseRefetch;

    /**
     * {@code lastModified} announced by the deferred SSE notification, as Unix epoch seconds.
     * Null means "unknown" — the pending re-fetch is then always issued, never dropped.
     */
    private Long pendingSseLastModified;

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
     * step 4), so initialization never contends with the first tick. The first poll fires one
     * interval after start.
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
     * Requests an out-of-band fetch on behalf of SSE (a {@code refetchConfig} notification, or a
     * reconnect catch-up). Runs immediately if the fetch slot is free; otherwise it is deferred
     * and coalesced into a single pending re-fetch. Safe to call from any thread; does not disturb
     * the ticker cadence.
     *
     * @param announcedLastModified {@code lastModified} from the SSE message as Unix epoch
     *                              seconds, or null when unknown (a reconnect catch-up). A
     *                              deferred re-fetch is dropped only when the completed fetch
     *                              already returned a config at least this new.
     */
    synchronized void triggerSseRefetch(Long announcedLastModified) {
        if (worker == null || worker.isShutdown()) {
            return;
        }
        if (isFetchInFlight()) {
            if (pendingSseRefetch) {
                // coalesce: a null (unknown) announcement wins, since it can never be dropped
                pendingSseLastModified = (pendingSseLastModified == null || announcedLastModified == null)
                        ? null
                        : Math.max(pendingSseLastModified, announcedLastModified);
            } else {
                pendingSseRefetch = true;
                pendingSseLastModified = announcedLastModified;
            }
            log.debug("fetch in flight; deferring SSE re-fetch (lastModified={})", pendingSseLastModified);
            return;
        }
        submit();
    }

    /** Stops polling and shuts down the ticker and worker, interrupting any in-flight fetch. */
    synchronized void stop() {
        pendingSseRefetch = false;
        pendingSseLastModified = null;
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

    /**
     * Poll tick. Skipped outright if a fetch is already in flight: the in-flight fetch already does
     * what this tick wanted, and the tick learned nothing, so it must not be reported as an outcome.
     */
    private synchronized void onTick() {
        if (worker == null || worker.isShutdown()) {
            return;
        }
        if (isFetchInFlight()) {
            // Emits nothing — in particular, does not reset the grace timer (KB section 2.1.1).
            log.debug("poll tick skipped: a fetch is already in flight");
            return;
        }
        submit();
    }

    private boolean isFetchInFlight() {
        Future<?> current = fetchInFlight;
        return current != null && !current.isDone();
    }

    /** Caller must hold the monitor. */
    private void submit() {
        try {
            fetchInFlight = worker.submit(this::runFetch);
        } catch (RejectedExecutionException e) {
            // worker shut down between the guard and submit; ignore
        }
    }

    private void runFetch() {
        FetchResult result = null;
        try {
            result = poll();
        } finally {
            issuePendingSseRefetch(result);
        }
    }

    /**
     * Issues the deferred SSE re-fetch, if any, now that the slot is free. Runs on the worker
     * thread, so the re-fetch it submits queues behind the current task rather than overlapping it.
     *
     * @param completed the result of the fetch that just finished, or null if it threw
     */
    private synchronized void issuePendingSseRefetch(FetchResult completed) {
        if (!pendingSseRefetch || worker == null || worker.isShutdown()) {
            return;
        }
        Long announced = pendingSseLastModified;
        Long observed = completed == null ? null : completed.getLastModifiedEpochSeconds();
        if (announced != null && observed != null && observed >= announced) {
            log.debug("dropping deferred SSE re-fetch: completed fetch already returned "
                    + "Last-Modified {} >= announced {}", observed, announced);
            pendingSseRefetch = false;
            pendingSseLastModified = null;
            return;
        }
        log.debug("issuing deferred SSE re-fetch (announced lastModified={})", announced);
        pendingSseRefetch = false;
        pendingSseLastModified = null;
        submit();
    }

    private FetchResult poll() {
        try {
            FetchResult result = fetchFlags.call();

            // No request was made because another fetch held the slot. Neither success nor
            // failure: emit nothing, and in particular do not reset the grace timer.
            if (result.isSkipped()) {
                log.debug("fetch skipped: already in progress");
                return result;
            }

            if (result.isNotModified()) {
                log.debug("flags not modified");
                emitSuccess.run();
                return result;
            }

            // 429: rate limited; still serving cached values (spec §2.3)
            if (result.isRateLimited()) {
                log.debug("rate limited; serving cached values");
                return result;
            }

            if (!result.isSuccess()) {
                emitFailure.accept("HTTP " + result.getStatusCode());
                return result;
            }

            String body = result.getBody();
            if (body == null || body.isEmpty()) {
                emitFailure.accept("empty response body");
                return result;
            }

            List<String> changedKeys = storeFlags.apply(body);
            emitSuccess.run();

            if (changedKeys != null && !changedKeys.isEmpty()) {
                log.info("flag configuration changed: {}", changedKeys);
                emitChange.accept(changedKeys);
            }
            return result;
        } catch (Exception e) {
            // Only shutdown interrupts this thread now that contended ticks are skipped rather
            // than cancelled; treat an interrupt as "stopping", not as a poll failure.
            if (isInterrupted(e)) {
                log.debug("poll interrupted (shutting down)");
                return null;
            }
            log.warn("poll failed", e);
            emitFailure.accept(e.getMessage());
            return null;
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
