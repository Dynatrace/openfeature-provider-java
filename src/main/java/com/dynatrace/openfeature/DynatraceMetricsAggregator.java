package com.dynatrace.openfeature;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory aggregate map backing the Dynatrace evaluation-metrics hook.
 *
 * <p>Implements the two-level structure {@code map[flagKey][variant, reason, errorCode, timestampSec]}.
 * Drains are synchronized with writes so evaluations cannot be written into a snapshot after it has been serialized.
 */
final class DynatraceMetricsAggregator {
    private static final Logger log = LoggerFactory.getLogger(DynatraceMetricsAggregator.class);

    static final int DEFAULT_MAX_DISTINCT_FLAGS = 10_000;

    private Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregate>> aggregateMetrics = new HashMap<>();
    private final int hardCapSize;
    private String version;

    DynatraceMetricsAggregator() {
        this(DEFAULT_MAX_DISTINCT_FLAGS);
    }

    DynatraceMetricsAggregator(int hardCapSize) {
        if (hardCapSize <= 0) {
            throw new IllegalArgumentException("hardCapSize must be positive");
        }
        this.hardCapSize = hardCapSize;
    }

    synchronized void record(String flagKey, String variant, String reason, String errorCode, long timestampSec) {
        if (!aggregateMetrics.containsKey(flagKey) && aggregateMetrics.size() >= hardCapSize) {
            log.debug("Dropping metrics due to max aggregator size");
            return;
        }
        // Per-flag dimension cardinality is naturally bounded by (variant × reason × errorCode ×
        // 10s-time-bucket) and the flush interval, so the inner map doesn't need a separate cap.
        EvaluationMetricKey key = new EvaluationMetricKey(variant, reason, errorCode, timestampSec);
        aggregateMetrics.computeIfAbsent(flagKey, ignored -> new HashMap<>())
                .computeIfAbsent(key, EvaluationMetricAggregate::new)
                .increment();
    }

    /** Records the latest config version observed in flag-evaluation metadata. */
    synchronized void setVersion(String version) {
        if (version != null && !version.isBlank()) {
            this.version = version;
        }
    }

    synchronized String getVersion() {
        return version;
    }

    /** Number of distinct flag keys currently in the live map. */
    synchronized int size() {
        return aggregateMetrics.size();
    }

    /**
     * Drains the live aggregate map and returns the previous contents for the caller to flush.
     * The version is preserved because it remains valid for the just-drained batch.
     * The drain is a copy of the live map to avoid aliasing issues.
     */
    synchronized DrainedBatch drain() {
        Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregate>> previous = aggregateMetrics;
        aggregateMetrics = new HashMap<>();
        String drainedVersion = version;
        return new DrainedBatch(previous, drainedVersion);
    }

    /**
     * Restores a previously drained <em>unversioned</em> batch back into the live map so a later
     * drain can pick it up under the eventual version. Used by the flusher to recover from a
     * failed POST during the pre-version-observation window.
     *
     * <p>Versioned batches must <strong>not</strong> be merged: doing so would mix their counts
     * into whatever version the aggregator subsequently observes, breaking the per-version
     * batch boundary. Callers that hold a versioned {@link DrainedBatch} after a failed delivery
     * must keep it as a separate batch (e.g. requeue it in the flusher's sealed-batch queue)
     * and never call this method with it.
     *
     * <p>Incoming aggregates are copied — never aliased — so the live map cannot be mutated
     * through any reference still held by the caller. Not bounded by {@code hardCapSize}:
     * this path is restoring previously-accepted data and dropping it would lose committed
     * counts. The hard cap applies only to <em>new</em> dimensions arriving via {@link #record}.
     *
     * @throws IllegalStateException if {@code batch.getVersion() != null}
     */
    synchronized void mergeUnversioned(DrainedBatch batch) {
        if (batch.getVersion() != null) {
            throw new IllegalStateException(
                    "cannot merge a versioned batch back into the live aggregator: "
                            + "doing so would mix version '" + batch.getVersion() + "' counts "
                            + "into the next observed version. Keep the batch separate instead.");
        }
        for (Map.Entry<String, Map<EvaluationMetricKey, EvaluationMetricAggregate>> outer
                : batch.getEvaluations().entrySet()) {
            // Pre-size the inner map so the incoming entries land without a rehash.
            int incomingSize = outer.getValue().size();
            Map<EvaluationMetricKey, EvaluationMetricAggregate> flagEvaluations =
                    aggregateMetrics.computeIfAbsent(outer.getKey(),
                            ignored -> sizedHashMap(incomingSize));
            for (Map.Entry<EvaluationMetricKey, EvaluationMetricAggregate> inner : outer.getValue().entrySet()) {
                EvaluationMetricAggregate incoming = inner.getValue();
                EvaluationMetricAggregate aggregate = flagEvaluations.get(inner.getKey());
                if (aggregate == null) {
                    EvaluationMetricAggregate copy = new EvaluationMetricAggregate(inner.getKey());
                    copy.add(incoming.getCount());
                    flagEvaluations.put(inner.getKey(), copy);
                } else {
                    aggregate.add(incoming.getCount());
                }
            }
        }
    }

    /** For testing only. Production flush path uses {@link #drain()}. */
    synchronized Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregate>> snapshot() {
        Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregate>> copy =
                sizedHashMap(aggregateMetrics.size());
        for (Map.Entry<String, Map<EvaluationMetricKey, EvaluationMetricAggregate>> outer : aggregateMetrics.entrySet()) {
            copy.put(outer.getKey(), Collections.unmodifiableMap(new HashMap<>(outer.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Creates a {@link HashMap} sized to hold {@code expectedSize} entries without triggering a
     * rehash at the default 0.75 load factor.
     *
     * <p>Java 19 added {@code HashMap.newHashMap(int)} that does exactly this; this project
     * targets Java 11, so we replicate the formula explicitly.
     */
    private static <K, V> HashMap<K, V> sizedHashMap(int expectedSize) {
        return new HashMap<>((int) Math.ceil(expectedSize / 0.75d));
    }

    static final class DrainedBatch {
        private final Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregate>> evaluations;
        private final String version;

        private DrainedBatch(Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregate>> evaluations,
                             String version) {
            this.evaluations = evaluations;
            this.version = version;
        }

        boolean isEmpty() {
            return evaluations.isEmpty();
        }

        /**
         * Returns an unmodifiable view of the drained two-level evaluations map.
         */
        Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregate>> getEvaluations() {
            Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregate>> wrapped =
                    sizedHashMap(evaluations.size());
            for (Map.Entry<String, Map<EvaluationMetricKey, EvaluationMetricAggregate>> e : evaluations.entrySet()) {
                wrapped.put(e.getKey(), Collections.unmodifiableMap(e.getValue()));
            }
            return Collections.unmodifiableMap(wrapped);
        }

        String getVersion() {
            return version;
        }
    }
}
