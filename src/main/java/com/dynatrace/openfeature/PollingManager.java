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

/**
 * Manages periodic polling of flag configurations.
 *
 * <p>Runs a single-threaded daemon scheduler that periodically fetches,
 * applies, and notifies about flag configuration changes.
 */
final class PollingManager {

    private static final Logger log = LoggerFactory.getLogger(PollingManager.class);

    private final Callable<FetchResult> fetchFlags;
    private final Function<String, List<String>> storeFlags;
    private final Consumer<List<String>> emitChange;
    private final Runnable emitSuccess;
    private final Consumer<String> emitFailure;
    private final Duration pollInterval;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pollFuture;

    /**
     * @param fetchFlags   called to fetch flag configuration JSON from the CDN
     * @param storeFlags   called to store the fetched JSON into the flag engine; returns changed flag keys
     * @param emitChange   called with the list of changed flag keys after a successful store
     * @param emitSuccess  called after any successful poll (including 304 not-modified)
     * @param emitFailure  called with a reason string when a poll fails
     * @param pollInterval delay between consecutive polls
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
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dt-provider-poller");
            t.setDaemon(true);
            return t;
        });
        pollFuture = scheduler.scheduleWithFixedDelay(
                this::poll,
                pollInterval.toMillis(),
                pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * Stops polling and shuts down the scheduler.
     */
    void stop() {
        if (pollFuture != null) {
            pollFuture.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private void poll() {
        try {
            FetchResult result = fetchFlags.call();

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
            log.warn("poll failed", e);
            emitFailure.accept(e.getMessage());
        }
    }
}
