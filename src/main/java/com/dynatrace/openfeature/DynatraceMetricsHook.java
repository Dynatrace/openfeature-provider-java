package com.dynatrace.openfeature;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Locale;
import java.util.HashMap;
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
 *   <li>{@code __default__} sentinel applied for code-default and error evaluations, or when
 *       the resolver does not return a variant</li>
 *   <li>error codes and reasons normalized to lowercase to match the line protocol</li>
 *   <li>config {@code version} read from flagd's per-evaluation {@code metadata.version}
 *       and stored on the aggregator as a batch-level property</li>
 * </ul>
 */
final class DynatraceMetricsHook implements Hook<Object> {

    private static final Logger log = LoggerFactory.getLogger(DynatraceMetricsHook.class);

    static final String DEFAULT_VARIANT = "__default__";
    static final String DEFAULT_REASON = Reason.DEFAULT.name().toLowerCase(Locale.ROOT);
    static final String UNKNOWN_REASON = Reason.UNKNOWN.name().toLowerCase(Locale.ROOT);
    static final String ERROR_REASON = Reason.ERROR.name().toLowerCase(Locale.ROOT);
    static final String VERSION_METADATA_KEY = "version";

    private static final Map<String, String> REASON_NAMES = new HashMap<>();
    private static final Map<ErrorCode, String> ERROR_CODE_NAMES = new EnumMap<>(ErrorCode.class);

    static {
        for (Reason reason : Reason.values()) {
            String normalized = reason.name().toLowerCase(Locale.ROOT);
            REASON_NAMES.put(reason.name(), normalized);
            REASON_NAMES.put(normalized, normalized);
        }
        for (ErrorCode errorCode : ErrorCode.values()) {
            ERROR_CODE_NAMES.put(errorCode, errorCode.name().toLowerCase(Locale.ROOT));
        }
    }

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
        String rawReason = details.getReason();
        String errorCode = normalizeErrorCode(details.getErrorCode());
        aggregator.record(
                flagKey,
                normalizeVariant(details.getVariant(), rawReason, errorCode),
                normalizeReason(rawReason, errorCode),
                errorCode,
                currentTimestampBucketSec(),
                version);

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

    private String normalizeVariant(String variant, String reason, String errorCode) {
        if (errorCode != null || isDefaultReason(reason) || variant == null || variant.isBlank()) {
            return DEFAULT_VARIANT;
        }
        return variant;
    }

    private String normalizeReason(String reason, String errorCode) {
        if (errorCode != null) {
            return ERROR_REASON;
        }
        if (reason == null || reason.isBlank()) {
            return UNKNOWN_REASON;
        }
        return normalizeEnumName(reason);
    }

    private boolean isDefaultReason(String reason) {
        return Reason.DEFAULT.name().equals(reason) || DEFAULT_REASON.equals(reason);
    }

    private String normalizeErrorCode(Enum<?> errorCode) {
        if (errorCode == null) {
            return null;
        }
        if (errorCode instanceof ErrorCode) {
            return ERROR_CODE_NAMES.get(errorCode);
        }
        return normalizeEnumName(errorCode.name());
    }

    private String normalizeEnumName(String value) {
        String normalizedReason = REASON_NAMES.get(value);
        return normalizedReason != null ? normalizedReason : value.toLowerCase(Locale.ROOT);
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
        long epochSec = clock.millis() / 1_000L;
        return Math.floorDiv(epochSec, 10) * 10;
    }
}
