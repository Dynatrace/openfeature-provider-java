package com.dynatrace.openfeature;

import dev.openfeature.contrib.tools.flagd.core.FlagdCore;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderState;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynatraceProviderTest {

    private static final String TEST_KEY =
            "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5";
    private static final String FLAG_CONFIG = TestFixtures.FLAG_CONFIG;

    private MockWebServer server;
    private HttpClient httpClient;

    /** no-sleep retry strategy for fast tests */
    private static final RetryStrategy NO_DELAY_RETRY =
            new RetryStrategy(new Random(42), ms -> {});

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
        // Guard against singleton state leaking between tests that use OpenFeatureAPI.getInstance().
        OpenFeatureAPI.getInstance().shutdown();
    }

    private DynatraceProviderOptions options() {
        return DynatraceProviderOptions.builder(TEST_KEY)
                .cdnBaseUrl(server.url("/server/").toString())
                .build();
    }

    private DynatraceProvider provider() {
        DynatraceProviderOptions opts = options();
        CdnFetcher fetcher = new CdnFetcher(opts.getCdnUrl(), httpClient, NO_DELAY_RETRY);
        return new DynatraceProvider(opts, new FlagdCore(), fetcher);
    }

    /** provider with short poll interval and grace period for state management tests */
    private DynatraceProvider fastProvider() {
        return fastProvider(Duration.ofMillis(200));
    }

    private DynatraceProvider fastProvider(Duration gracePeriod) {
        DynatraceProviderOptions opts = options();
        CdnFetcher fetcher = new CdnFetcher(opts.getCdnUrl(), httpClient, NO_DELAY_RETRY);
        return new DynatraceProvider(opts, new FlagdCore(), fetcher,
                Duration.ofMillis(50), gracePeriod);
    }

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
    void providerNameIsDynatrace() {
        DynatraceProvider p = provider();
        assertEquals("dynatrace", p.getMetadata().getName());
        p.shutdown();
    }

    @Test
    @SuppressWarnings("rawtypes") // mirrors the SDK's raw List<Hook> contract on getProviderHooks()
    void exposesDynatraceProviderHook() {
        DynatraceProviderOptions options = DynatraceProviderOptions.builder(TEST_KEY).build();
        DynatraceProvider p = new DynatraceProvider(options, new FlagdCore(), httpClient);

        List<Hook> hooks = p.getProviderHooks();

        assertEquals(1, hooks.size());
        assertTrue(hooks.get(0) instanceof DynatraceMetricsHook);
        assertThrows(UnsupportedOperationException.class,
                () -> hooks.add(new DynatraceMetricsHook(new DynatraceMetricsAggregator())));

        p.shutdown();
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
                    metricsServer.url("/").toString(),
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
    void applyFlagConfigSealsBatchForCurrentVersionBeforeSwitching() throws Exception {
        try (MockWebServer metricsServer = new MockWebServer()) {
            metricsServer.start();
            // Both POSTs are delivered by the shutdown flush: sealed v1 batch first, live v2 batch second.
            metricsServer.enqueue(new MockResponse().setResponseCode(202));
            metricsServer.enqueue(new MockResponse().setResponseCode(202));
            server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));

            DynatraceProviderOptions options = DynatraceProviderOptions.builder(TEST_KEY)
                    .cdnBaseUrl(server.url("/server/").toString())
                    .metricsBaseUrl(metricsServer.url("/").toString())
                    .metricsFlushInterval(Duration.ofHours(1)) // no periodic flush during the test
                    .build();
            DynatraceProvider p = new DynatraceProvider(options, new FlagdCore(), httpClient);
            p.initialize(new ImmutableContext());

            DynatraceMetricsHook hook = (DynatraceMetricsHook) p.getProviderHooks().get(0);
            HookContext<Object> ctx = booleanHookContext("my-bool-flag");

            // Simulate two evaluations against config v1.
            FlagEvaluationDetails<Object> v1Details = FlagEvaluationDetails.<Object>builder()
                    .flagKey("my-bool-flag").variant("on").reason("STATIC")
                    .flagMetadata(ImmutableMetadata.builder().addString("version", "1741780800").build())
                    .build();
            hook.finallyAfter(ctx, v1Details, Collections.emptyMap());
            hook.finallyAfter(ctx, v1Details, Collections.emptyMap());

            // CDN poller detects a new config — seals the v1 batch and schedules an async flush.
            // storeFlags must return immediately without blocking.
            String newConfig = FLAG_CONFIG.replace("1741780800", "1741780900");
            p.storeFlags(newConfig);

            // Simulate one evaluation against config v2 (goes to the fresh live batch).
            FlagEvaluationDetails<Object> v2Details = FlagEvaluationDetails.<Object>builder()
                    .flagKey("my-bool-flag").variant("on").reason("STATIC")
                    .flagMetadata(ImmutableMetadata.builder().addString("version", "1741780900").build())
                    .build();
            hook.finallyAfter(ctx, v2Details, Collections.emptyMap());

            // Shutdown delivers sealed v1 batch first, then live v2 batch.
            p.shutdown();

            RecordedRequest v1Batch = metricsServer.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(v1Batch, "expected v1 sealed batch on shutdown");
            String v1Body = v1Batch.getBody().readUtf8();
            assertTrue(v1Body.contains("\"version\":\"1741780800\""), v1Body);
            assertTrue(v1Body.contains("\"count\":2"), v1Body);

            RecordedRequest v2Batch = metricsServer.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(v2Batch, "expected v2 live batch on shutdown");
            String v2Body = v2Batch.getBody().readUtf8();
            assertTrue(v2Body.contains("\"version\":\"1741780900\""), v2Body);
            assertFalse(v2Body.contains("1741780800"), "v1 version must not appear in v2 batch: " + v2Body);
        }
    }

    @Test
    void storeFlagsThrowsAfterShutdown() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));

        DynatraceProvider p = provider();
        p.initialize(new ImmutableContext());
        p.shutdown();

        assertThrows(IllegalStateException.class, () -> p.storeFlags(FLAG_CONFIG));
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
                    metricsServer.url("/").toString(),
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

            DynatraceMetricsAggregator aggregator = new DynatraceMetricsAggregator();
            aggregator.setVersion("1741780800");
            aggregator.record("header-redesign", "control", "targeting_match", null, 1741780820L);
            EvaluationMetricsFlusher flusher = new EvaluationMetricsFlusher(
                    aggregator,
                    httpClient,
                    TEST_KEY,
                    metricsServer.url("/").toString(),
                    Duration.ofMinutes(5),
                    1,
                    "1.2.3",
                    "dynatrace");

            flusher.shutdown();

            assertEquals(1L, aggregator.size());
            RecordedRequest req = metricsServer.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(req);
            assertEquals("/v1/metrics", req.getPath());
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
                "https://example.invalid/",
                Duration.ofMinutes(5),
                1_000,
                "1.2.3",
                "dynatrace");

        flusher.shutdown();

        assertEquals(1L, aggregator.size(),
                "drained batch must be requeued even when send() throws an unchecked exception");
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
                    metricsServer.url("/").toString(),
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

    @Test
    void sdkClientEvaluationTriggersProviderMetricsHook() throws Exception {
        try (MockWebServer metricsServer = new MockWebServer()) {
            metricsServer.start();
            metricsServer.enqueue(new MockResponse().setResponseCode(202));
            server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));

            DynatraceProviderOptions options = DynatraceProviderOptions.builder(TEST_KEY)
                    .cdnBaseUrl(server.url("/server/").toString())
                    .metricsBaseUrl(metricsServer.url("/").toString())
                    .metricsFlushInterval(Duration.ofMinutes(5))
                    .build();

            OpenFeatureAPI api = OpenFeatureAPI.getInstance();
            try {
                api.setProviderAndWait(new DynatraceProvider(options, new FlagdCore(), httpClient));

                assertTrue(api.getClient().getBooleanValue("my-bool-flag", false, new ImmutableContext()));

                api.shutdown();
                RecordedRequest req = metricsServer.takeRequest(5, TimeUnit.SECONDS);
                assertNotNull(req, "expected metrics POST from SDK-triggered provider hook");
                assertEquals("/v1/metrics", req.getPath());
                assertTrue(req.getBody().readUtf8().contains("\"flagKey\":\"my-bool-flag\""));
            } finally {
                api.shutdown();
            }
        }
    }

    @Test
    void shutdownFlushesAggregatedMetricsToTheConfiguredEndpoint() throws Exception {
        // Two MockWebServers: one serves the flag config, the other receives metrics POSTs.
        try (MockWebServer metricsServer = new MockWebServer()) {
            metricsServer.start();
            metricsServer.enqueue(new MockResponse().setResponseCode(202));
            server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));

            DynatraceProviderOptions options = DynatraceProviderOptions.builder(TEST_KEY)
                    .cdnBaseUrl(server.url("/server/").toString())
                    .metricsBaseUrl(metricsServer.url("/").toString())
                    .metricsFlushInterval(Duration.ofMinutes(5)) // periodic flush should not fire during the test
                    .build();
            DynatraceProvider p = new DynatraceProvider(options, new FlagdCore(), httpClient);

            p.initialize(new ImmutableContext());

            // Drive the hook directly — the OpenFeature SDK calls finallyAfter in production;
            // here we bypass the SDK to keep this a focused provider unit test.
            DynatraceMetricsHook hook = (DynatraceMetricsHook) p.getProviderHooks().get(0);
            HookContext<Object> ctx = booleanHookContext("my-bool-flag");
            FlagEvaluationDetails<Object> details = FlagEvaluationDetails.<Object>builder()
                    .flagKey("my-bool-flag")
                    .variant("on")
                    .reason("STATIC")
                    .flagMetadata(ImmutableMetadata.builder().addString("version", "1741780800").build())
                    .build();
            hook.finallyAfter(ctx, details, Collections.emptyMap());

            p.shutdown();

            RecordedRequest req = metricsServer.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(req, "expected metrics POST on shutdown");
            assertEquals("POST", req.getMethod());
            assertEquals("/v1/metrics", req.getPath());
            assertEquals("application/json", req.getHeader("Content-Type"));
            assertEquals("Bearer " + TEST_KEY, req.getHeader(EvaluationMetricsFlusher.AUTHORIZATION_HEADER));
            String body = req.getBody().readUtf8();
            assertTrue(body.contains("\"providerName\":\"dynatrace\""), body);
            assertTrue(body.contains("\"flagKey\":\"my-bool-flag\""), body);
            assertTrue(body.contains("\"variant\":\"on\""), body);
            assertTrue(body.contains("\"evaluationReason\":\"static\""), body);
        }
    }

    @Test
    void metricsUrlDerivedFromKey() {
        // Geo segment in the key controls the metrics-endpoint hostname.
        DynatraceProviderOptions options = DynatraceProviderOptions.builder(
                "dt01.server_eu_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5").build();
        assertEquals("https://metrics.eu.fm.dynatrace.com/", options.getMetricsUrl());
    }

    @Test
    void metricsBaseUrlOverrideTrailingSlashIsAutoAppended() {
        DynatraceProviderOptions options = DynatraceProviderOptions.builder(TEST_KEY)
                .metricsBaseUrl("https://metrics.us.fm.dynatracelabs.com")
                .build();
        assertEquals("https://metrics.us.fm.dynatracelabs.com/", options.getMetricsUrl());
    }

    @Test
    void initializeFetchesConfigAndResolvesFlagsCorrectly() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(FLAG_CONFIG)
                .addHeader("ETag", "\"v1\""));

        DynatraceProvider p = provider();
        p.initialize(new ImmutableContext());

        ProviderEvaluation<Boolean> boolEval = p.getBooleanEvaluation("my-bool-flag", false, new ImmutableContext());
        assertTrue(boolEval.getValue());

        ProviderEvaluation<String> strEval = p.getStringEvaluation("my-string-flag", "default", new ImmutableContext());
        assertEquals("world", strEval.getValue());

        p.shutdown();
    }

    @Test
    void initializeThrowsOnHttpError() {
        for (int i = 0; i < RetryStrategy.MAX_ATTEMPTS; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }

        DynatraceProvider p = provider();

        assertThrows(Exception.class, () -> p.initialize(new ImmutableContext()));
        p.shutdown();
    }

    @Test
    void initializeThrowsOnEmptyBody() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(""));

        DynatraceProvider p = provider();

        assertThrows(Exception.class, () -> p.initialize(new ImmutableContext()));
        p.shutdown();
    }

    @Test
    void initializeThrowsOnInvalidJson() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("not-json"));

        DynatraceProvider p = provider();

        assertThrows(Exception.class, () -> p.initialize(new ImmutableContext()));
        p.shutdown();
    }

    @Test
    void unknownFlagReturnsErrorCode() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));

        DynatraceProvider p = provider();
        p.initialize(new ImmutableContext());

        ProviderEvaluation<Boolean> result = p.getBooleanEvaluation("no-such-flag", false, new ImmutableContext());
        assertEquals(ErrorCode.FLAG_NOT_FOUND, result.getErrorCode());

        p.shutdown();
    }

    @Test
    void shutdownIsIdempotent() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));

        DynatraceProvider p = provider();
        p.initialize(new ImmutableContext());

        assertDoesNotThrow(() -> {
            p.shutdown();
            p.shutdown();
            p.shutdown();
        });
    }

    // -- state management tests --

    @Test
    void stateIsReadyAfterInitialize() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));

        DynatraceProvider p = provider();
        p.initialize(new ImmutableContext());

        assertEquals(ProviderState.READY, p.getState());
        p.shutdown();
    }

    @Test
    void stateIsNotReadyBeforeInitialize() {
        DynatraceProvider p = provider();
        assertEquals(ProviderState.NOT_READY, p.getState());
        p.shutdown();
    }

    @Test
    void pollFailureTransitionsToStale() throws Exception {
        // init succeeds
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));
        // poll fails (enqueue enough for retries)
        for (int i = 0; i < RetryStrategy.MAX_ATTEMPTS; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }

        // long grace so state stays STALE during the assertion window
        DynatraceProvider p = fastProvider(Duration.ofSeconds(10));
        p.initialize(new ImmutableContext());

        // wait for a poll to happen and fail
        Thread.sleep(300);

        assertEquals(ProviderState.STALE, p.getState());
        p.shutdown();
    }

    @Test
    void gracePeriodExpiryTransitionsToError() throws Exception {
        // init succeeds
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));
        // all subsequent polls fail
        for (int i = 0; i < RetryStrategy.MAX_ATTEMPTS * 10; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }

        DynatraceProvider p = fastProvider();
        p.initialize(new ImmutableContext());

        // wait for poll failure + grace period (200ms) + buffer
        Thread.sleep(600);

        assertEquals(ProviderState.ERROR, p.getState());
        p.shutdown();
    }

    @Test
    void recoveryFromStaleToReady() throws Exception {
        // init succeeds
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));
        // first poll fails
        for (int i = 0; i < RetryStrategy.MAX_ATTEMPTS; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }
        // second poll succeeds (304 not modified)
        server.enqueue(new MockResponse().setResponseCode(304));

        DynatraceProvider p = fastProvider();
        p.initialize(new ImmutableContext());

        // wait for failure + recovery
        Thread.sleep(400);

        assertEquals(ProviderState.READY, p.getState());
        p.shutdown();
    }

    @Test
    void shutdownResetsStateToNotReady() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));

        DynatraceProvider p = provider();
        p.initialize(new ImmutableContext());
        assertEquals(ProviderState.READY, p.getState());

        p.shutdown();
        assertEquals(ProviderState.NOT_READY, p.getState());
    }

    // -- options tests --

    @Test
    void cdnUrlDerivedFromKey() {
        DynatraceProviderOptions options =
                DynatraceProviderOptions.builder(
                        "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5").build();
        assertEquals(
                "https://cdn.us.fm.dynatrace.com/server/"
                        + "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5.json",
                options.getCdnUrl());
    }

    @Test
    void cdnUrlUsesGeoFromKey() {
        DynatraceProviderOptions eu =
                DynatraceProviderOptions.builder(
                        "dt01.server_eu_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5").build();
        assertTrue(eu.getCdnUrl().contains("cdn.eu.fm.dynatrace.com/server/"));
    }

    @Test
    void builderAcceptsArbitraryAlphabeticGeo() {
        DynatraceProviderOptions de =
                DynatraceProviderOptions.builder(
                        "dt01.server_de_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5").build();
        assertTrue(de.getCdnUrl().contains("cdn.de.fm.dynatrace.com"));
    }

    @Test
    void builderTrimsWhitespaceFromKey() {
        DynatraceProviderOptions options = DynatraceProviderOptions
                .builder("  dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5  ").build();
        assertEquals(
                "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5",
                options.getKey());
    }

    @Test
    void publicKeyExposesOnlyPublicSegment() {
        DynatraceProviderOptions options = DynatraceProviderOptions.builder(TEST_KEY).build();
        assertEquals("dt01.server_us_abcdef1234", options.getPublicKey());
    }

    @Test
    void builderRejectsBlankKey() {
        assertThrows(IllegalArgumentException.class, () -> DynatraceProviderOptions.builder(""));
        assertThrows(IllegalArgumentException.class, () -> DynatraceProviderOptions.builder(null));
        assertThrows(IllegalArgumentException.class, () -> DynatraceProviderOptions.builder("   "));
    }

    @Test
    void builderRejectsInvalidKeyFormat() {
        // completely invalid
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("notavalidkey"));
        // old format is no longer valid
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_server_us_03ee0ef7cd4244bfb8b71fea8f783f53"));
        // missing private segment
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt01.server_us_abcdef1234"));
        // publicRandom too short (< 10 chars)
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt01.server_us_abc.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5"));
    }

    @Test
    void builderRejectsNonServerType() {
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder(
                        "dt01.web_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5"));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder(
                        "dt01.mobile_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5"));
    }

    @Test
    void builderRejectsInvalidGeo() {
        // hyphen in geo
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder(
                        "dt01.server_us-east_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5"));
        // uppercase geo
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder(
                        "dt01.server_US_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5"));
        // single-char geo (min is 2)
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder(
                        "dt01.server_u_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5"));
    }

    @Test
    void builderRejectsInvalidPrivateSegment() {
        // secret too short (< 32 hex chars)
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder(
                        "dt01.server_us_abcdef1234.abc123_a1b2c3d4e5"));
        // missing sha1 (no underscore after secret)
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder(
                        "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d"));
        // sha1 too short (< 10 chars)
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder(
                        "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_abc12"));
    }

    @Test
    void cdnBaseUrlWithoutTrailingSlashIsAutoAppended() {
        DynatraceProviderOptions options = DynatraceProviderOptions.builder(TEST_KEY)
                .cdnBaseUrl("https://cdn.example.com/server")
                .build();
        assertEquals(
                "https://cdn.example.com/server/"
                        + "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5.json",
                options.getCdnUrl());
    }

    @Test
    void builderRejectsBlankCdnBaseUrl() {
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder(TEST_KEY).cdnBaseUrl(""));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder(TEST_KEY).cdnBaseUrl(null));
    }

    @Test
    void builderUsesDefaultMetricsFlushIntervalIfInvalid() {
        assertEquals(DynatraceProviderOptions.DEFAULT_METRICS_FLUSH_INTERVAL, DynatraceProviderOptions.builder(TEST_KEY).metricsFlushInterval(Duration.ofNanos(500)).build().getMetricsFlushInterval());
    }

    @Test
    void shutdownBeforeInitializeIsSafe() {
        DynatraceProvider p = provider();
        assertDoesNotThrow(p::shutdown);
    }

    @Test
    void evaluationBeforeInitializeReturnsFlagNotFound() {
        DynatraceProvider p = provider();
        try {
            ProviderEvaluation<Boolean> result =
                    p.getBooleanEvaluation("any-flag", false, new ImmutableContext());
            assertEquals(ErrorCode.FLAG_NOT_FOUND, result.getErrorCode());
        } finally {
            p.shutdown();
        }
    }
}
