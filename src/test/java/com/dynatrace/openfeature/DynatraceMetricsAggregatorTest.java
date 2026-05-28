package com.dynatrace.openfeature;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    void metricsAggregatorDropsNewDimensionsBeyondMaxDistinctFlags() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator(2);
        aggregator.setVersion("1741780800");

        aggregator.record("flag-a", "v1", "static", null, 1741780820);
        aggregator.record("flag-b", "v1", "static", null, 1741780820);
        // max distinct flags reached — new dimensions are dropped
        aggregator.record("flag-c", "v1", "static", null, 1741780820);
        aggregator.record("flag-d", "v1", "static", null, 1741780820);
        // existing dimensions still accept count increments
        aggregator.record("flag-a", "v1", "static", null, 1741780820);

        assertEquals(2L, aggregator.size(), "size must not exceed max distinct flags");
        DynatraceMetricsAggregator.DrainedBatch batch = aggregator.drain();
        assertEquals(2L,
                batch.getEvaluations().get("flag-a").values().iterator().next().getCount(),
                "existing dimension's count should keep accumulating after max distinct flags reached");
        assertNull(batch.getEvaluations().get("flag-c"), "dropped flag must not appear in batch");
    }

    @Test
    void metricsAggregatorDropsRecordsWhenNoVersionSet() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        aggregator.record("header-redesign", "control", "targeting_match", null, 1741780820);

        assertEquals(0L, aggregator.size(), "evaluations must be dropped when no version is set");
        assertTrue(aggregator.drain().isEmpty());
    }

    @Test
    void metricsAggregatorDropsRecordOnVersionMismatch() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        aggregator.setVersion("v1");
        aggregator.record("flag-a", "on", "static", null, 1741780820, "v1");
        // attempt to record under a different version while map is non-empty
        aggregator.record("flag-b", "on", "static", null, 1741780820, "v2");
        assertEquals(1L, aggregator.size(), "record with mismatched version must be dropped");
        assertEquals("v1", aggregator.getVersion());
    }

    @Test
    void metricsAggregatorDropsRecordOnVersionMismatchEvenWhenMapIsEmpty() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        aggregator.setVersion("v1");
        aggregator.drain();
        // map is empty but version is still v1 — mismatch must still be rejected;
        // version transitions are now the provider's responsibility via setVersion()
        aggregator.record("flag-a", "on", "static", null, 1741780820, "v2");
        assertEquals(0L, aggregator.size(), "version mismatch must be dropped even on empty map");
        assertEquals("v1", aggregator.getVersion(), "version must not change via record()");
    }

    @Test
    void metricsAggregatorRejectsNonPositiveMaxDistinctFlags() {
        assertThrows(IllegalArgumentException.class, () -> new DynatraceMetricsAggregator(0));
        assertThrows(IllegalArgumentException.class, () -> new DynatraceMetricsAggregator(-1));
    }

    @Test
    void drainedBatchEvaluationsMapIsUnmodifiable() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        aggregator.setVersion("1741780800");
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
        aggregator.setVersion("1741780800");
        aggregator.record("flag", "a|b", "c", null, 1741780820);
        aggregator.record("flag", "a", "b|c", null, 1741780820);

        // Both dimensions must land in distinct inner-map slots — a string-concatenated key would
        // collide on the pipe character.
        assertEquals(2, aggregator.snapshot().get("flag").size());
    }

    @Test
    void concurrentRecordAndDrainDoesNotLoseEvaluations() throws Exception {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        aggregator.setVersion("1741780800");
        int workerCount = 4;
        int recordsPerWorker = 1_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workerCount);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();

        for (int worker = 0; worker < workerCount; worker++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < recordsPerWorker; i++) {
                        aggregator.record("flag", "variant", "static", null, 1741780820);
                    }
                } catch (Throwable t) {
                    workerFailure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            }, "metrics-aggregator-test-" + worker);
            thread.start();
        }

        long drainedCount = 0;
        start.countDown();
        while (!done.await(1, TimeUnit.MILLISECONDS)) {
            drainedCount += count(aggregator.drain());
        }
        drainedCount += count(aggregator.drain());

        assertNull(workerFailure.get(), "worker thread failed");
        assertEquals(workerCount * recordsPerWorker, drainedCount);
        assertTrue(aggregator.snapshot().isEmpty());
    }

    private long count(DynatraceMetricsAggregator.DrainedBatch batch) {
        return batch.getEvaluations().values().stream()
                .flatMap(flagEvaluations -> flagEvaluations.values().stream())
                .mapToLong(EvaluationMetricAggregate::getCount)
                .sum();
    }
}
