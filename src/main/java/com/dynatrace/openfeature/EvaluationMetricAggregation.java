package com.dynatrace.openfeature;

/**
 * One bucket of aggregated evaluations for a single
 * (variant, reason, errorCode, 10s timestamp bucket) tuple.
 *
 * <p>Dimension fields are stored once in the {@link EvaluationMetricKey} that identifies this
 * bucket; this class holds only the mutable {@code count}.
 */
final class EvaluationMetricAggregation {

    private final EvaluationMetricKey key;
    private long count;

    EvaluationMetricAggregation(EvaluationMetricKey key) {
        this.key = key;
    }

    void increment() {
        add(1);
    }

    void add(long delta) {
        count += delta;
    }

    String getVariant() {
        return key.getVariant();
    }

    String getReason() {
        return key.getReason();
    }

    String getErrorCode() {
        return key.getErrorCode();
    }

    long getTimestampSec() {
        return key.getTimestampSec();
    }

    long getCount() {
        return count;
    }

    @Override
    public String toString() {
        return "EvaluationMetricAggregation{key=" + key + ", count=" + count + '}';
    }
}
