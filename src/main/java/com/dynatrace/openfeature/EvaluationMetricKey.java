package com.dynatrace.openfeature;

import java.util.Objects;

final class EvaluationMetricKey {

    private final String variant;
    private final String reason;
    private final String errorCode;
    private final long timestampSec;

    EvaluationMetricKey(String variant, String reason, String errorCode, long timestampSec) {
        this.variant = variant;
        this.reason = reason;
        this.errorCode = errorCode;
        this.timestampSec = timestampSec;
    }

    String getVariant() {
        return variant;
    }

    String getReason() {
        return reason;
    }

    String getErrorCode() {
        return errorCode;
    }

    long getTimestampSec() {
        return timestampSec;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EvaluationMetricKey)) {
            return false;
        }
        EvaluationMetricKey that = (EvaluationMetricKey) o;
        return timestampSec == that.timestampSec
                && Objects.equals(variant, that.variant)
                && Objects.equals(reason, that.reason)
                && Objects.equals(errorCode, that.errorCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(variant, reason, errorCode, timestampSec);
    }

    @Override
    public String toString() {
        return "EvaluationMetricKey{variant='" + variant + "', reason='" + reason
                + "', errorCode='" + errorCode + "', timestampSec=" + timestampSec + '}';
    }
}
