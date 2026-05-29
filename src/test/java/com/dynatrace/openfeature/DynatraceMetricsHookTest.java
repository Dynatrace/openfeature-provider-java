package com.dynatrace.openfeature;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableMetadata;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DynatraceMetricsHookTest {

    private HookContext<Object> booleanHookContext(String flagKey) {
        return HookContext.from(
                flagKey,
                FlagValueType.BOOLEAN,
                () -> "test-client",
                () -> DynatraceProvider.PROVIDER_NAME,
                new ImmutableContext(),
                false);
    }

    @Test
    void metricsHookAggregatesEvaluationsByDocumentedDimensionKey() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        DynatraceMetricsHook hook = new DynatraceMetricsHook(
                aggregator,
                Clock.fixed(Instant.ofEpochSecond(1741780823), ZoneOffset.UTC));
        HookContext<Object> ctx = booleanHookContext("header-redesign");
        FlagEvaluationDetails<Object> details = FlagEvaluationDetails.<Object>builder()
                .flagKey("header-redesign")
                .variant("control")
                .reason("TARGETING_MATCH")
                .build();

        hook.finallyAfter(ctx, details, Collections.emptyMap());
        hook.finallyAfter(ctx, details, Collections.emptyMap());

        EvaluationMetricAggregate aggregate = aggregator.snapshot()
                .get("header-redesign")
                .get(new EvaluationMetricKey("control", "targeting_match", null, 1741780820));
        assertNotNull(aggregate);
        assertEquals("control", aggregate.getVariant());
        assertEquals("targeting_match", aggregate.getReason());
        assertNull(aggregate.getErrorCode());
        assertEquals(1741780820, aggregate.getTimestampSec());
        assertEquals(2, aggregate.getCount());
    }

    @Test
    void metricsHookUsesDefaultSentinelAndLowercaseErrorCodeForErrors() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        DynatraceMetricsHook hook = new DynatraceMetricsHook(
                aggregator,
                Clock.fixed(Instant.ofEpochSecond(1741780829), ZoneOffset.UTC));
        HookContext<Object> ctx = booleanHookContext("deleted-flag");
        FlagEvaluationDetails<Object> details = FlagEvaluationDetails.<Object>builder()
                .flagKey("deleted-flag")
                .errorCode(ErrorCode.FLAG_NOT_FOUND)
                .build();

        hook.finallyAfter(ctx, details, Collections.emptyMap());

        EvaluationMetricAggregate aggregate = aggregator.snapshot()
                .get("deleted-flag")
                .get(new EvaluationMetricKey("__default__", "error", "flag_not_found", 1741780820));
        assertNotNull(aggregate);
        assertEquals("__default__", aggregate.getVariant());
        assertEquals("error", aggregate.getReason());
        assertEquals("flag_not_found", aggregate.getErrorCode());
        assertEquals(1741780820, aggregate.getTimestampSec());
        assertEquals(1, aggregate.getCount());
    }

    @Test
    void metricsHookUsesDefaultSentinelForDefaultReason() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        DynatraceMetricsHook hook = new DynatraceMetricsHook(
                aggregator,
                Clock.fixed(Instant.ofEpochSecond(1741780829), ZoneOffset.UTC));
        HookContext<Object> ctx = booleanHookContext("fallback-flag");
        FlagEvaluationDetails<Object> details = FlagEvaluationDetails.<Object>builder()
                .flagKey("fallback-flag")
                .variant("off")
                .reason("DEFAULT")
                .build();

        hook.finallyAfter(ctx, details, Collections.emptyMap());

        EvaluationMetricAggregate aggregate = aggregator.snapshot()
                .get("fallback-flag")
                .get(new EvaluationMetricKey("__default__", "default", null, 1741780820));
        assertNotNull(aggregate);
        assertEquals("__default__", aggregate.getVariant());
        assertEquals("default", aggregate.getReason());
    }

    @Test
    void metricsHookForcesErrorReasonAndDefaultSentinelForErrors() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        DynatraceMetricsHook hook = new DynatraceMetricsHook(
                aggregator,
                Clock.fixed(Instant.ofEpochSecond(1741780829), ZoneOffset.UTC));
        HookContext<Object> ctx = booleanHookContext("mismatched-type");
        FlagEvaluationDetails<Object> details = FlagEvaluationDetails.<Object>builder()
                .flagKey("mismatched-type")
                .variant("on")
                .reason("TARGETING_MATCH")
                .errorCode(ErrorCode.TYPE_MISMATCH)
                .build();

        hook.finallyAfter(ctx, details, Collections.emptyMap());

        EvaluationMetricAggregate aggregate = aggregator.snapshot()
                .get("mismatched-type")
                .get(new EvaluationMetricKey("__default__", "error", "type_mismatch", 1741780820));
        assertNotNull(aggregate);
        assertEquals("__default__", aggregate.getVariant());
        assertEquals("error", aggregate.getReason());
        assertEquals("type_mismatch", aggregate.getErrorCode());
    }

    @Test
    void metricsHookCapturesConfigVersionFromFlagMetadata() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        DynatraceMetricsHook hook = new DynatraceMetricsHook(
                aggregator,
                Clock.fixed(Instant.ofEpochSecond(1741780823), ZoneOffset.UTC));
        HookContext<Object> ctx = booleanHookContext("header-redesign");
        FlagEvaluationDetails<Object> details = FlagEvaluationDetails.<Object>builder()
                .flagKey("header-redesign")
                .variant("control")
                .reason("TARGETING_MATCH")
                .flagMetadata(ImmutableMetadata.builder()
                        .addString("version", "1741780800")
                        .build())
                .build();

        hook.finallyAfter(ctx, details, Collections.emptyMap());

        assertEquals("1741780800", aggregator.getVersion());
    }
}
