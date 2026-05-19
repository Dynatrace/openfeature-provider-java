package com.dynatrace.openfeature;

import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;

/**
 * Provider-owned {@code finallyAfter} hook that captures every flag evaluation into the
 * Dynatrace evaluation-metrics aggregate map.
 *
 * <p>See the <em>evaluation-metrics</em> technical scoping doc for the dimensions and bucketing
 * rules implemented here:
 * <ul>
 *   <li>per-record dimensions: {@code flagKey}, {@code variant}, {@code reason}, {@code errorCode}</li>
 *   <li>10-second epoch bucket via {@code Math.floorDiv(epochSec, 10) * 10}</li>
 *   <li>{@code __default__} sentinel applied when the resolver does not return a variant
 *       (typically on errors)</li>
 *   <li>error codes and reasons normalized to lowercase to match the line protocol</li>
 *   <li>config {@code version} read from flagd's per-evaluation {@code metadata.version}
 *       and stored on the aggregator as a batch-level property</li>
 * </ul>
 */
final class DynatraceMetricsHook implements Hook<Object> {

    private static final Logger log = LoggerFactory.getLogger(DynatraceMetricsHook.class);

    static final String DEFAULT_VARIANT = "__default__";
    static final String UNKNOWN_REASON = "unknown";
    static final String ERROR_REASON = "error";
    static final String VERSION_METADATA_KEY = "version";

    private final DynatraceMetricsAggregator aggregator;
    private final Clock clock;
    private final Runnable onRecord;

    DynatraceMetricsHook(DynatraceMetricsAggregator aggregator) {
        this(aggregator, Clock.systemUTC(), () -> {});
    }

    // visible for testing
    DynatraceMetricsHook(DynatraceMetricsAggregator aggregator, Clock clock) {
        this(aggregator, clock, () -> {});
    }

    DynatraceMetricsHook(DynatraceMetricsAggregator aggregator, Clock clock, Runnable onRecord) {
        this.aggregator = aggregator;
        this.clock = clock;
        this.onRecord = onRecord == null ? () -> {} : onRecord;
    }

    @Override
    public void finallyAfter(HookContext<Object> ctx, FlagEvaluationDetails<Object> details, Map<String, Object> hints) {
        String flagKey = flagKey(ctx, details);
        if (flagKey == null || flagKey.isBlank()) {
            return;
        }

        String version = readVersion(details);
        if (version != null) {
            aggregator.setVersion(version);
        }

        String errorCode = normalizeErrorCode(details.getErrorCode());
        aggregator.record(
                flagKey,
                normalizeVariant(details.getVariant()),
                normalizeReason(details.getReason(), errorCode),
                errorCode,
                currentTimestampBucketSec());

        try {
            onRecord.run();
        } catch (RuntimeException e) {
            // Never block the evaluation path on flush bookkeeping.
            log.warn("metrics hook onRecord callback failed: {}", e.toString());
        }
    }

    private String flagKey(HookContext<Object> ctx, FlagEvaluationDetails<Object> details) {
        if (details.getFlagKey() != null && !details.getFlagKey().isBlank()) {
            return details.getFlagKey();
        }
        return ctx.getFlagKey();
    }

    private String normalizeVariant(String variant) {
        return variant == null || variant.isBlank() ? DEFAULT_VARIANT : variant;
    }

    private String normalizeReason(String reason, String errorCode) {
        if (reason == null || reason.isBlank()) {
            return errorCode == null ? UNKNOWN_REASON : ERROR_REASON;
        }
        return normalizeEnumName(reason);
    }

    private String normalizeErrorCode(Enum<?> errorCode) {
        return errorCode == null ? null : normalizeEnumName(errorCode.name());
    }

    private String normalizeEnumName(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private String readVersion(FlagEvaluationDetails<Object> details) {
        ImmutableMetadata metadata = details.getFlagMetadata();
        if (metadata == null) {
            return null;
        }
        try {
            return metadata.getString(VERSION_METADATA_KEY);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private long currentTimestampBucketSec() {
        long epochSec = clock.instant().getEpochSecond();
        return Math.floorDiv(epochSec, 10) * 10;
    }
}
