package com.dynatrace.openfeature;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluationMetricsFlusherTest {

    private static final String TEST_KEY =
            "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5";

    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        // nothing to clean up — each test owns its own MockWebServer in try-with-resources
    }

    @Test
    void evaluationMetricsFlusherBuildsDocumentedJsonPayload() {
        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        aggregator.record("header-redesign", "control", "targeting_match", null, 1741780820L);
        aggregator.record("deleted-flag", "__default__", "error", "flag_not_found", 1741780820L);

        String body = EvaluationMetricsPayload.build(
                aggregator.drain().getEvaluations(),
                "1741780800",
                "1.2.3",
                "dynatrace");

        assertTrue(body.startsWith("{") && body.endsWith("}"), "expected JSON object: " + body);
        assertFalse(body.contains(",,"), "unexpected double comma in JSON: " + body);
        assertTrue(body.contains("\"evaluations\":["), body);
        assertTrue(body.contains("\"version\":\"1741780800\""), body);
        assertTrue(body.contains("\"sdkType\":\"server\""), body);
        assertTrue(body.contains("\"sdkVersion\":\"1.2.3\""), body);
        assertTrue(body.contains("\"providerName\":\"dynatrace\""), body);
        assertTrue(body.contains(
                "\"flagKey\":\"header-redesign\",\"variant\":\"control\","
                        + "\"evaluationReason\":\"targeting_match\","
                        + "\"timestampSec\":1741780820,\"count\":1"), body);
        assertTrue(body.contains(
                "\"flagKey\":\"deleted-flag\",\"variant\":\"__default__\","
                        + "\"evaluationReason\":\"error\",\"errorCode\":\"flag_not_found\","
                        + "\"timestampSec\":1741780820,\"count\":1"), body);
    }

    @Test
    void triggerFlushPostsSealedBatchAsynchronously() throws Exception {
        try (MockWebServer metricsServer = new MockWebServer()) {
            metricsServer.start();
            metricsServer.enqueue(new MockResponse().setResponseCode(202));

            DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
            aggregator.setVersion("1741780800");
            aggregator.record("header-redesign", "control", "targeting_match", null, 1741780820L);
            EvaluationMetricsFlusher flusher = new EvaluationMetricsFlusher(
                    aggregator,
                    httpClient,
                    TEST_KEY,
                    metricsServer.url("/v1/metrics").toString(),
                    Duration.ofMinutes(5),
                    1_000,
                    "1.2.3",
                    "dynatrace");
            flusher.start();

            DynatraceMetricsAggregator.DrainedBatch sealed = aggregator.drain();
            flusher.enqueueSealedBatch(sealed);
            flusher.triggerFlush();

            assertEquals(0L, aggregator.size(), "live aggregator should be empty after drain");
            RecordedRequest req = metricsServer.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(req, "expected HTTP POST from triggerFlush");
            assertEquals("/v1/metrics", req.getPath());
            assertTrue(req.getBody().readUtf8().contains("\"version\":\"1741780800\""));

            flusher.shutdown();
        }
    }

    @Test
    void flusherPostsToConfiguredMetricsUrlExactly() throws Exception {
        try (MockWebServer metricsServer = new MockWebServer()) {
            metricsServer.start();
            metricsServer.enqueue(new MockResponse().setResponseCode(202));

            DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
            aggregator.setVersion("1741780800");
            aggregator.record("header-redesign", "control", "targeting_match", null, 1741780820L);
            EvaluationMetricsFlusher flusher = new EvaluationMetricsFlusher(
                    aggregator,
                    httpClient,
                    TEST_KEY,
                    metricsServer.url("/custom/metrics").toString(),
                    Duration.ofMinutes(5),
                    1_000,
                    "1.2.3",
                    "dynatrace");

            flusher.shutdown();

            RecordedRequest req = metricsServer.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(req, "expected HTTP POST during shutdown");
            assertEquals("/custom/metrics", req.getPath());
        }
    }

    @Test
    void evaluationMetricsFlusherRequeuesBatchWhenNoVersionAvailable() throws Exception {
        try (MockWebServer metricsServer = new MockWebServer()) {
            metricsServer.start();

            DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
            aggregator.record("header-redesign", "control", "targeting_match", null, 1741780820L);
            // no version set on the aggregator — simulates pre-version-observation window
            EvaluationMetricsFlusher flusher = new EvaluationMetricsFlusher(
                    aggregator,
                    httpClient,
                    TEST_KEY,
                    metricsServer.url("/v1/metrics").toString(),
                    Duration.ofMinutes(5),
                    1_000,
                    "1.2.3",
                    "dynatrace");

            flusher.shutdown();

            // data must be requeued, no HTTP request should have been made
            assertEquals(1L, aggregator.size(), "evaluations should be requeued when version is absent");
            assertNull(metricsServer.takeRequest(200, TimeUnit.MILLISECONDS),
                    "no HTTP request expected when version is absent");
        }
    }

    @Test
    void evaluationMetricsFlusherRequeuesBatchWhenEndpointRejectsIt() throws Exception {
        try (MockWebServer metricsServer = new MockWebServer()) {
            metricsServer.start();
            metricsServer.enqueue(new MockResponse().setResponseCode(500));
            metricsServer.enqueue(new MockResponse().setResponseCode(202));

            DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
            aggregator.setVersion("1741780800");
            aggregator.record("header-redesign", "control", "targeting_match", null, 1741780820L);
            EvaluationMetricsFlusher flusher = new EvaluationMetricsFlusher(
                    aggregator,
                    httpClient,
                    TEST_KEY,
                    metricsServer.url("/v1/metrics").toString(),
                    Duration.ofMinutes(5),
                    1,
                    "1.2.3",
                    "dynatrace");
            flusher.start();

            flusher.onRecord();

            // Sync on the first (failed) request first — onRecord schedules work on the flusher's
            // single-threaded executor, so checking aggregator.size() right after onRecord would
            // race the scheduler thread. By the time the server has received the request, drain()
            // has definitely run and emptied the live map.
            RecordedRequest failedReq = metricsServer.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(failedReq);
            assertEquals("/v1/metrics", failedReq.getPath());
            assertEquals(0L, aggregator.size(),
                    "failed batch must not be merged back into the live aggregator — that would lose "
                            + "the captured version on the next config rotation");

            flusher.triggerFlush();
            RecordedRequest retryReq = metricsServer.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(retryReq, "versioned failed batch should retry as a sealed batch");
            assertTrue(retryReq.getBody().readUtf8().contains("\"version\":\"1741780800\""));

            flusher.shutdown();
        }
    }

    @Test
    void failedLiveFlushRetriesWithOriginalVersionAfterConfigChange() throws Exception {
        try (MockWebServer metricsServer = new MockWebServer()) {
            metricsServer.start();
            metricsServer.enqueue(new MockResponse().setResponseCode(500));
            metricsServer.enqueue(new MockResponse().setResponseCode(202));
            metricsServer.enqueue(new MockResponse().setResponseCode(202));

            DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
            aggregator.setVersion("1741780800");
            aggregator.record("header-redesign", "control", "targeting_match", null, 1741780820L);
            EvaluationMetricsFlusher flusher = new EvaluationMetricsFlusher(
                    aggregator,
                    httpClient,
                    TEST_KEY,
                    metricsServer.url("/v1/metrics").toString(),
                    Duration.ofMinutes(5),
                    1,
                    "1.2.3",
                    "dynatrace");
            flusher.start();

            flusher.onRecord();
            RecordedRequest failedReq = metricsServer.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(failedReq, "expected first live flush attempt");
            assertTrue(failedReq.getBody().readUtf8().contains("\"version\":\"1741780800\""));

            aggregator.setVersion("1741780900");
            aggregator.record("new-flag", "on", "static", null, 1741780910L);

            flusher.shutdown();

            RecordedRequest retryReq = metricsServer.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(retryReq, "expected retry of failed old-version batch");
            String retryBody = retryReq.getBody().readUtf8();
            assertTrue(retryBody.contains("\"version\":\"1741780800\""), retryBody);
            assertTrue(retryBody.contains("\"flagKey\":\"header-redesign\""), retryBody);
            assertFalse(retryBody.contains("new-flag"), retryBody);

            RecordedRequest liveReq = metricsServer.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(liveReq, "expected new-version live batch");
            String liveBody = liveReq.getBody().readUtf8();
            assertTrue(liveBody.contains("\"version\":\"1741780900\""), liveBody);
            assertTrue(liveBody.contains("\"flagKey\":\"new-flag\""), liveBody);
            assertFalse(liveBody.contains("header-redesign"), liveBody);
        }
    }

    @Test
    @SuppressWarnings("unchecked") // mocking HttpClient.send requires raw HttpResponse.BodyHandler
    void flushRequeuesBatchWhenHttpClientThrowsUncheckedException() throws Exception {
        // Simulates a misbehaving HttpClient that throws an unchecked exception not in the
        // declared throws list (IOException / InterruptedException). Without the try/finally
        // requeue, the drained batch would be silently lost.
        HttpClient throwingClient = mock(HttpClient.class);
        when(throwingClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new RuntimeException("boom"));

        DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
        aggregator.setVersion("1741780800");
        aggregator.record("header-redesign", "control", "targeting_match", null, 1741780820L);
        EvaluationMetricsFlusher flusher = new EvaluationMetricsFlusher(
                aggregator,
                throwingClient,
                TEST_KEY,
                "https://example.invalid/v1/metrics",
                Duration.ofMinutes(5),
                1_000,
                "1.2.3",
                "dynatrace");

        flusher.shutdown();

        assertEquals(0L, aggregator.size(),
                "versioned failed batch must be preserved outside the live aggregator");
    }

    @Test
    void capacityFlushDoesNotImmediatelyRetryAfterFailure() throws Exception {
        try (MockWebServer metricsServer = new MockWebServer()) {
            metricsServer.start();
            metricsServer.enqueue(new MockResponse().setResponseCode(500));

            DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
            aggregator.setVersion("1741780800");
            EvaluationMetricsFlusher flusher = new EvaluationMetricsFlusher(
                    aggregator,
                    httpClient,
                    TEST_KEY,
                    metricsServer.url("/v1/metrics").toString(),
                    Duration.ofMinutes(5),
                    1,
                    "1.2.3",
                    "dynatrace");
            flusher.start();

            aggregator.record("header-redesign", "control", "targeting_match", null, 1741780820L);
            flusher.onRecord();
            assertNotNull(metricsServer.takeRequest(5, TimeUnit.SECONDS));

            aggregator.record("header-redesign", "control", "targeting_match", null, 1741780820L);
            flusher.onRecord();
            assertNull(metricsServer.takeRequest(200, TimeUnit.MILLISECONDS));

            metricsServer.enqueue(new MockResponse().setResponseCode(500));
            flusher.shutdown();
        }
    }
}
