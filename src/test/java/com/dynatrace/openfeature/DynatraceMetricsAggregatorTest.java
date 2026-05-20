package com.dynatrace.openfeature;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynatraceMetricsAggregatorTest {

    @Test
    void metricsAggregatorSwapClearsLiveMapAndPreservesPreviousEntries() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        aggregator.setVersion("1741780800");
        aggregator.record("header-redesign", "control", "targeting_match", null, 1741780820);

        DynatraceMetricsAggregator.DrainedBatch batch = aggregator.drain();

        assertEquals(1, batch.getEvaluations().get("header-redesign").size());
        assertTrue(aggregator.snapshot().isEmpty());
        assertEquals(0L, aggregator.size());
        assertEquals("1741780800", batch.getVersion());
        assertEquals("1741780800", aggregator.getVersion());
    }

    @Test
    void metricsAggregatorCapturesVersionWithDrainedBatch() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        aggregator.setVersion("1741780800");
        aggregator.record("header-redesign", "control", "targeting_match", null, 1741780820);

        DynatraceMetricsAggregator.DrainedBatch batch = aggregator.drain();
        aggregator.setVersion("1741780900");

        assertEquals("1741780800", batch.getVersion());
        assertEquals("1741780900", aggregator.getVersion());
    }

    @Test
    void metricsAggregatorDropsNewDimensionsBeyondHardCap() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator(2);

        aggregator.record("flag-a", "v1", "static", null, 1741780820);
        aggregator.record("flag-b", "v1", "static", null, 1741780820);
        // hard cap reached — new dimensions are dropped
        aggregator.record("flag-c", "v1", "static", null, 1741780820);
        aggregator.record("flag-d", "v1", "static", null, 1741780820);
        // existing dimensions still accept count increments
        aggregator.record("flag-a", "v1", "static", null, 1741780820);

        assertEquals(2L, aggregator.size(), "size must not exceed hard cap");
        DynatraceMetricsAggregator.DrainedBatch batch = aggregator.drain();
        assertEquals(2L,
                batch.getEvaluations().get("flag-a").values().iterator().next().getCount(),
                "existing dimension's count should keep accumulating after hard cap");
        assertNull(batch.getEvaluations().get("flag-c"), "dropped flag must not appear in batch");
    }

    @Test
    void metricsAggregatorMergeCopiesAggregatesSoCallerCannotMutateLiveMap() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        aggregator.record("flag-a", "v1", "static", null, 1741780820);
        DynatraceMetricsAggregator.DrainedBatch batch = aggregator.drain();
        aggregator.merge(batch.getEvaluations());

        // Mutate the original aggregate the caller still holds — the live map must not see it.
        EvaluationMetricAggregate original =
                batch.getEvaluations().get("flag-a").values().iterator().next();
        original.add(99);

        EvaluationMetricAggregate live = aggregator.snapshot()
                .get("flag-a")
                .get(new EvaluationMetricKey("v1", "static", null, 1741780820));
        assertEquals(1L, live.getCount(),
                "live aggregate must not alias the caller-visible instance");
    }

    @Test
    void metricsAggregatorRejectsNonPositiveHardCap() {
        assertThrows(IllegalArgumentException.class, () -> new DynatraceMetricsAggregator(0));
        assertThrows(IllegalArgumentException.class, () -> new DynatraceMetricsAggregator(-1));
    }

    @Test
    void drainedBatchEvaluationsMapIsUnmodifiable() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        aggregator.record("flag-a", "v1", "static", null, 1741780820);
        DynatraceMetricsAggregator.DrainedBatch batch = aggregator.drain();

        Map<String, Map<EvaluationMetricKey, EvaluationMetricAggregate>> evaluations = batch.getEvaluations();
        assertThrows(UnsupportedOperationException.class, () -> evaluations.put("x", null));
        assertThrows(UnsupportedOperationException.class,
                () -> evaluations.get("flag-a").put(
                        new EvaluationMetricKey("v2", "static", null, 1741780820),
                        new EvaluationMetricAggregate(new EvaluationMetricKey("v2", "static", null, 1741780820))));
    }

    @Test
    void metricsAggregatorUsesTypedDimensionKeyWithoutDelimiterCollisions() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        aggregator.record("flag", "a|b", "c", null, 1741780820);
        aggregator.record("flag", "a", "b|c", null, 1741780820);

        // Both dimensions must land in distinct inner-map slots — a string-concatenated key would
        // collide on the pipe character.
        assertEquals(2, aggregator.snapshot().get("flag").size());
    }
}
