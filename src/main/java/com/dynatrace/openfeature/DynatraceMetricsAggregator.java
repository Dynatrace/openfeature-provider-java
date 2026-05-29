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

    private Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregation>> aggregateMetrics = new HashMap<>();
    private final int maxDistinctFlags;
    private String version;

    DynatraceMetricsAggregator() {
        this(DEFAULT_MAX_DISTINCT_FLAGS);
    }

    DynatraceMetricsAggregator(int maxDistinctFlags) {
        if (maxDistinctFlags <= 0) {
            throw new IllegalArgumentException("maxDistinctFlags must be positive");
        }
        this.maxDistinctFlags = maxDistinctFlags;
    }

    synchronized void record(String flagKey, String variant, String reason, String errorCode, long timestampSec) {
        record(flagKey, variant, reason, errorCode, timestampSec, null);
    }

    synchronized void record(String flagKey, String variant, String reason, String errorCode, long timestampSec, String evaluationVersion) {
        if (version == null) {
            log.debug("dropping evaluation for '{}': no config version observed yet", flagKey);
            return;
        }
        if (evaluationVersion != null && !version.equals(evaluationVersion)) {
            return;
        }
        if (!aggregateMetrics.containsKey(flagKey) && aggregateMetrics.size() >= maxDistinctFlags) {
            log.debug("Dropping metrics due to max aggregator size");
            return;
        }
        EvaluationMetricKey key = new EvaluationMetricKey(variant, reason, errorCode, timestampSec);
        aggregateMetrics.computeIfAbsent(flagKey, ignored -> new HashMap<>())
                .computeIfAbsent(key, EvaluationMetricAggregation::new)
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
        Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregation>> previous = aggregateMetrics;
        aggregateMetrics = new HashMap<>();
        String drainedVersion = version;
        return new DrainedBatch(previous, drainedVersion);
    }

    /** For testing only. Production flush path uses {@link #drain()}. */
    synchronized Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregation>> snapshot() {
        Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregation>> copy =
                sizedHashMap(aggregateMetrics.size());
        for (Map.Entry<String, Map<EvaluationMetricKey, EvaluationMetricAggregation>> outer : aggregateMetrics.entrySet()) {
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
        private final Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregation>> evaluations;
        private final String version;

        private DrainedBatch(Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregation>> evaluations,
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
        Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregation>> getEvaluations() {
            Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregation>> wrapped =
                    sizedHashMap(evaluations.size());
            for (Map.Entry<String, Map<EvaluationMetricKey, EvaluationMetricAggregation>> e : evaluations.entrySet()) {
                wrapped.put(e.getKey(), Collections.unmodifiableMap(e.getValue()));
            }
            return Collections.unmodifiableMap(wrapped);
        }

        String getVersion() {
            return version;
        }
    }
}
