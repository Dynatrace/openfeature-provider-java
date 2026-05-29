package com.dynatrace.openfeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduled flusher that drains the {@link DynatraceMetricsAggregator}, serializes the
 * documented Dynatrace evaluation-metrics payload, and POSTs it to the configured metrics
 * ingest endpoint.
 *
 * <p>Server-SDK flush triggers (per the evaluation-metrics scoping doc):
 * <ul>
 *   <li>periodic — fixed-delay scheduling</li>
 *   <li>capacity — {@link #onRecord()} called by the hook after each evaluation</li>
 *   <li>version-rotation — {@link #triggerFlush()} called by {@code applyFlagConfig} after
 *       sealing the current batch via
 *       {@link #enqueueSealedBatch(DynatraceMetricsAggregator.DrainedBatch)}</li>
 *   <li>shutdown — {@link #shutdown()} performs a final synchronous flush</li>
 * </ul>
 *
 * <p>All HTTP work happens on a dedicated daemon executor, never on the evaluation path.
 */
final class EvaluationMetricsFlusher {

    private static final Logger log = LoggerFactory.getLogger(EvaluationMetricsFlusher.class);

    static final String AUTHORIZATION_HEADER = "Authorization";
    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Cooldown applied to out-of-band flushes after a failed delivery, to avoid
     * hammering a struggling endpoint between periodic flush ticks.
     */
    static final Duration FAILED_CAPACITY_RETRY_COOLDOWN = Duration.ofSeconds(30);

    /** Maximum number of sealed batches held in the queue at any time. */
    static final int MAX_SEALED_BATCHES = 10;

    private final DynatraceMetricsAggregator aggregator;
    private final HttpClient httpClient;
    private final URI metricsUri;
    private final String authorizationHeader;
    private final Duration flushInterval;
    private final int capacityThreshold;
    private final String sdkVersion;
    private final String providerName;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicBoolean outOfBandFlushScheduled = new AtomicBoolean(false);
    private final AtomicInteger sealedBatchCount = new AtomicInteger(0);
    private volatile long nextOutOfBandFlushAllowedNanos = 0L;
    private ScheduledFuture<?> scheduledTask;

    /**
     * Queue of pre-drained batches sealed by version-rotation calls to
     * {@link #enqueueSealedBatch}. Flushed before the live aggregator on every flush cycle
     * to preserve per-version batch ordering.
     */
    private final Deque<DynatraceMetricsAggregator.DrainedBatch> sealedBatches =
            new ConcurrentLinkedDeque<>();

    EvaluationMetricsFlusher(
            DynatraceMetricsAggregator aggregator,
            HttpClient httpClient,
            String sdkKey,
            String metricsUrl,
            Duration flushInterval,
            int capacityThreshold,
            String sdkVersion,
            String providerName) {
        this.aggregator = aggregator;
        this.httpClient = httpClient;
        this.metricsUri = URI.create(metricsUrl);
        this.authorizationHeader = "Bearer " + sdkKey;
        this.flushInterval = flushInterval;
        this.capacityThreshold = capacityThreshold;
        this.sdkVersion = sdkVersion;
        this.providerName = providerName;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "dynatrace-metrics-flusher");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts periodic flushing. Idempotent; safe to call once per provider lifecycle. */
    void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        long ms = flushInterval.toMillis();
        scheduledTask = scheduler.scheduleWithFixedDelay(this::flushQuietly, ms, ms, TimeUnit.MILLISECONDS);
        log.debug("evaluation metrics flusher started (interval={}ms, url={})", ms, metricsUri);
    }

    /**
     * Accepts a pre-drained batch for delivery ahead of the next periodic tick. The batch is
     * appended to the sealed-batch queue and will be delivered before any live-aggregator data
     * on the next flush cycle, preserving per-version ordering. No-op for empty batches.
     */
    void enqueueSealedBatch(DynatraceMetricsAggregator.DrainedBatch batch) {
        if (batch != null && !batch.isEmpty()) {
            if (sealedBatchCount.incrementAndGet() > MAX_SEALED_BATCHES) {
                sealedBatchCount.decrementAndGet();
                log.warn("sealed batch queue full ({} batches); dropping incoming batch for version {} to prevent unbounded growth",
                        MAX_SEALED_BATCHES, batch.getVersion());
                return;
            }
            sealedBatches.offerLast(batch);
        }
    }

    /**
     * Schedules an out-of-band flush on the flusher's executor without blocking the caller.
     * Safe to call before {@link #start()}.
     *
     * <p>Unlike {@link #onRecord()}, this method does <em>not</em> coalesce with an in-flight
     * flush — each call submits a fresh task to the scheduler. This is intentional: callers
     * (config rotation, shutdown-style code paths) need the guarantee that their just-enqueued
     * sealed batch will be picked up promptly even if another flush is still completing. The
     * extra task is cheap when there is nothing to deliver (empty deque + empty aggregator).
     */
    void triggerFlush() {
        if (shutdown.get()) {
            return;
        }
        try {
            scheduler.execute(this::flushQuietly);
        } catch (RejectedExecutionException ignored) {
            // Scheduler is shutting down; the shutdown() flush will drain remaining metrics.
        }
    }

    /**
     * Capacity callback invoked by the hook after each {@code finallyAfter}. If the live
     * aggregate map has crossed the capacity threshold, schedule an out-of-band flush. Never
     * blocks the calling thread.
     */
    void onRecord() {
        if (!started.get() || shutdown.get()) {
            return;
        }
        if (aggregator.size() < capacityThreshold) {
            return;
        }
        if (System.nanoTime() < nextOutOfBandFlushAllowedNanos) {
            return;
        }
        scheduleOutOfBandFlush();
    }

    /** Performs a final synchronous flush and stops the scheduler. Idempotent. */
    void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        try {
            scheduler.submit(this::flushQuietly).get(HTTP_TIMEOUT.toSeconds() + 2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            log.warn("final metrics flush did not complete: {}", e.toString());
        } finally {
            scheduler.shutdownNow();
        }
    }

    private void scheduleOutOfBandFlush() {
        if (!outOfBandFlushScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.execute(() -> {
                try {
                    flushQuietly();
                } finally {
                    outOfBandFlushScheduled.set(false);
                }
            });
        } catch (RejectedExecutionException ignored) {
            outOfBandFlushScheduled.set(false);
            // Scheduler is shutting down; the shutdown() flush will drain remaining metrics.
        }
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("evaluation metrics flush failed: {}", e.toString());
        }
    }

    private void flush() {
        // Flush sealed version-rotation batches first to preserve per-version ordering.
        // try/finally so an unexpected throw from flushBatch can't drop the batch on the floor —
        // we always either deliver or requeue at the front of the deque.
        DynatraceMetricsAggregator.DrainedBatch sealed;
        while ((sealed = sealedBatches.pollFirst()) != null) {
            boolean delivered = false;
            try {
                delivered = flushBatch(sealed);
            } finally {
                if (delivered) {
                    sealedBatchCount.decrementAndGet();
                } else {
                    sealedBatches.offerFirst(sealed);
                }
            }
            if (!delivered) {
                return;
            }
        }

        // Then flush whatever has accumulated in the live aggregator. Same try/finally pattern:
        // if flushBatch throws after drain(), we must requeue the snapshot so counts aren't lost.
        DynatraceMetricsAggregator.DrainedBatch live = aggregator.drain();
        if (live.isEmpty()) {
            return;
        }
        boolean delivered = false;
        try {
            delivered = flushBatch(live);
        } finally {
            if (!delivered) {
                sealedBatchCount.incrementAndGet();
                sealedBatches.offerFirst(live);
            }
        }
    }

    /**
     * Attempts to POST {@code batch} to the ingestion endpoint.
     * Returns {@code true} if delivered successfully; {@code false} if the caller should
     * requeue the batch for the next flush cycle.
     */
    private boolean flushBatch(DynatraceMetricsAggregator.DrainedBatch batch) {
        String body = EvaluationMetricsPayload.build(
                batch.getEvaluations(), batch.getVersion(), sdkVersion, providerName);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(metricsUri)
                    .header("Content-Type", "application/json")
                    .header(AUTHORIZATION_HEADER, authorizationHeader)
                    .timeout(HTTP_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 == 2) {
                nextOutOfBandFlushAllowedNanos = 0L;
                log.debug("evaluation metrics flush ok (status={}, payload bytes={})", status, body.length());
                return true;
            }
            log.warn("evaluation metrics flush returned HTTP {}; payload bytes={}", status, body.length());
        } catch (IllegalArgumentException | IOException e) {
            log.warn("evaluation metrics flush HTTP error: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("evaluation metrics flush interrupted");
        } catch (RuntimeException e) {
            log.warn("evaluation metrics flush unexpected error: {}", e.toString());
        }
        nextOutOfBandFlushAllowedNanos = System.nanoTime() + FAILED_CAPACITY_RETRY_COOLDOWN.toNanos();
        return false;
    }

}
