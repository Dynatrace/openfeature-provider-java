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
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

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

    private String mockCdnUrl() {
        return server.url("/server/").toString() + TEST_KEY + ".json";
    }

    private DynatraceProvider provider() {
        String origin = server.url("/").toString().replaceAll("/+$", "");
        return new DynatraceProvider(TEST_KEY,
                DynatraceProviderOptions.builder().configOrigin(origin).build());
    }

    /** provider with short poll interval and grace period for state management tests */
    private DynatraceProvider fastProvider() {
        return fastProvider(Duration.ofMillis(200));
    }

    private DynatraceProvider fastProvider(Duration gracePeriod) {
        CdnFetcher fetcher = new CdnFetcher(mockCdnUrl(), httpClient, NO_DELAY_RETRY);
        return new DynatraceProvider(TEST_KEY, mockCdnUrl(), new FlagdCore(), fetcher,
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
    void sdkKeyConstructorCreatesProviderInNotReadyState() {
        DynatraceProvider p = new DynatraceProvider(TEST_KEY);
        assertEquals(ProviderState.NOT_READY, p.getState());
        p.shutdown();
    }

    @Test
    void nullOptionsIsTreatedAsNoOptions() {
        DynatraceProvider p = new DynatraceProvider(TEST_KEY, null);
        assertEquals(ProviderState.NOT_READY, p.getState());
        p.shutdown();
    }

    @Test
    void sdkKeyWithOptionsConstructorCreatesProviderInNotReadyState() {
        DynatraceProvider p = new DynatraceProvider(TEST_KEY,
                DynatraceProviderOptions.builder().configOrigin("https://my-proxy.example.com").build());
        assertEquals(ProviderState.NOT_READY, p.getState());
        p.shutdown();
    }

    @Test
    @SuppressWarnings("rawtypes") // mirrors the SDK's raw List<Hook> contract on getProviderHooks()
    void exposesDynatraceProviderHook() {
        DynatraceProvider p = new DynatraceProvider(TEST_KEY, DynatraceProviderOptions.builder().build(),
                new FlagdCore(), httpClient);

        List<Hook> hooks = p.getProviderHooks();

        assertEquals(1, hooks.size());
        assertTrue(hooks.get(0) instanceof DynatraceMetricsHook);
        assertThrows(UnsupportedOperationException.class,
                () -> hooks.add(new DynatraceMetricsHook(new DynatraceMetricsAggregator())));

        p.shutdown();
    }

    @Test
    void applyFlagConfigSealsBatchForCurrentVersionBeforeSwitching() throws Exception {
        try (MockWebServer metricsServer = new MockWebServer()) {
            metricsServer.start();
            // Both POSTs are delivered by the shutdown flush: sealed v1 batch first, live v2 batch second.
            metricsServer.enqueue(new MockResponse().setResponseCode(202));
            metricsServer.enqueue(new MockResponse().setResponseCode(202));
            server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));

            DynatraceProviderOptions options = DynatraceProviderOptions.builder()
                    .configOrigin(server.url("/").toString().replaceAll("/+$", ""))
                    .metricsUrl(metricsServer.url("/v1/metrics").toString())
                    .metricsFlushInterval(Duration.ofHours(1)) // no periodic flush during the test
                    .build();
            DynatraceProvider p = new DynatraceProvider(TEST_KEY, options, new FlagdCore(), httpClient);
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
    void sdkClientEvaluationTriggersProviderMetricsHook() throws Exception {
        try (MockWebServer metricsServer = new MockWebServer()) {
            metricsServer.start();
            metricsServer.enqueue(new MockResponse().setResponseCode(202));
            server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));

            DynatraceProviderOptions options = DynatraceProviderOptions.builder()
                    .configOrigin(server.url("/").toString().replaceAll("/+$", ""))
                    .metricsUrl(metricsServer.url("/v1/metrics").toString())
                    .metricsFlushInterval(Duration.ofMinutes(5))
                    .build();

            OpenFeatureAPI api = OpenFeatureAPI.getInstance();
            try {
                api.setProviderAndWait(new DynatraceProvider(TEST_KEY, options, new FlagdCore(), httpClient));

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

            DynatraceProviderOptions options = DynatraceProviderOptions.builder()
                    .configOrigin(server.url("/").toString().replaceAll("/+$", ""))
                    .metricsUrl(metricsServer.url("/v1/metrics").toString())
                    .metricsFlushInterval(Duration.ofMinutes(5)) // periodic flush should not fire during the test
                    .build();
            DynatraceProvider p = new DynatraceProvider(TEST_KEY, options, new FlagdCore(), httpClient);

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
        assertEquals("https://metrics.eu.fm.dynatrace.com/v1/metrics",
                DynatraceProvider.buildMetricsUrl(
                        "dt01.server_eu_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5", null));
    }

    @Test
    void metricsUrlOverrideUsesFullEndpoint() {
        DynatraceProviderOptions options = DynatraceProviderOptions.builder()
                .metricsUrl("https://metrics.example.com/custom/metrics")
                .build();
        assertEquals("https://metrics.example.com/custom/metrics",
                DynatraceProvider.buildMetricsUrl(TEST_KEY, options));
    }

    @Test
    void metricsUrlOverrideTrimsWhitespace() {
        DynatraceProviderOptions options = DynatraceProviderOptions.builder()
                .metricsUrl("  https://metrics.example.com/v1/metrics  ")
                .build();
        assertEquals("https://metrics.example.com/v1/metrics",
                DynatraceProvider.buildMetricsUrl(TEST_KEY, options));
    }

    @Test
    void builderRejectsBlankMetricsUrl() {
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder().metricsUrl(""));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder().metricsUrl(null));
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
        for (int i = 0; i < 5; i++) {
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
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));
        for (int i = 0; i < RetryStrategy.MAX_ATTEMPTS; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }

        DynatraceProvider p = fastProvider(Duration.ofSeconds(10));
        p.initialize(new ImmutableContext());

        Thread.sleep(300);

        assertEquals(ProviderState.STALE, p.getState());
        p.shutdown();
    }

    @Test
    void gracePeriodExpiryTransitionsToError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));
        for (int i = 0; i < RetryStrategy.MAX_ATTEMPTS * 10; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }

        DynatraceProvider p = fastProvider();
        p.initialize(new ImmutableContext());

        Thread.sleep(600);

        assertEquals(ProviderState.ERROR, p.getState());
        p.shutdown();
    }

    @Test
    void recoveryFromStaleToReady() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));
        for (int i = 0; i < RetryStrategy.MAX_ATTEMPTS; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }
        server.enqueue(new MockResponse().setResponseCode(304));

        DynatraceProvider p = fastProvider();
        p.initialize(new ImmutableContext());

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
        assertEquals(
                "https://cdn.us.fm.dynatrace.com/server/"
                        + "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5.json",
                DynatraceProvider.buildCdnUrl(
                        "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5", null));
    }

    @Test
    void cdnUrlUsesGeoFromKey() {
        assertTrue(DynatraceProvider.buildCdnUrl(
                "dt01.server_eu_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5", null)
                .contains("cdn.eu.fm.dynatrace.com/server/"));
    }

    @Test
    void builderAcceptsArbitraryAlphabeticGeo() {
        assertTrue(DynatraceProvider.buildCdnUrl(
                "dt01.server_de_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5", null)
                .contains("cdn.de.fm.dynatrace.com"));
    }

    @Test
    void buildCdnUrlTrimsWhitespaceFromKey() {
        assertTrue(DynatraceProvider.buildCdnUrl(
                "  dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5  ", null)
                .contains("dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5"));
    }

    @Test
    void constructorRejectsBlankKey() {
        assertThrows(IllegalArgumentException.class, () -> new DynatraceProvider(""));
        assertThrows(IllegalArgumentException.class, () -> new DynatraceProvider((String) null));
        assertThrows(IllegalArgumentException.class, () -> new DynatraceProvider("   "));
    }

    @Test
    void constructorRejectsInvalidKeyFormat() {
        // completely invalid
        assertThrows(IllegalArgumentException.class, () -> new DynatraceProvider("notavalidkey"));
        // old format is no longer valid
        assertThrows(IllegalArgumentException.class, () ->
                new DynatraceProvider("dt_server_us_03ee0ef7cd4244bfb8b71fea8f783f53"));
        // missing private segment
        assertThrows(IllegalArgumentException.class, () ->
                new DynatraceProvider("dt01.server_us_abcdef1234"));
        // publicRandom too short (< 10 chars)
        assertThrows(IllegalArgumentException.class, () ->
                new DynatraceProvider("dt01.server_us_abc.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5"));
    }

    @Test
    void constructorRejectsNonServerType() {
        assertThrows(IllegalArgumentException.class, () ->
                new DynatraceProvider("dt01.web_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5"));
        assertThrows(IllegalArgumentException.class, () ->
                new DynatraceProvider("dt01.mobile_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5"));
    }

    @Test
    void constructorRejectsInvalidGeo() {
        // hyphen in geo
        assertThrows(IllegalArgumentException.class, () ->
                new DynatraceProvider("dt01.server_us-east_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5"));
        // uppercase geo
        assertThrows(IllegalArgumentException.class, () ->
                new DynatraceProvider("dt01.server_US_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5"));
        // single-char geo (min is 2)
        assertThrows(IllegalArgumentException.class, () ->
                new DynatraceProvider("dt01.server_u_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5"));
    }

    @Test
    void constructorRejectsInvalidPrivateSegment() {
        // secret too short (< 32 hex chars)
        assertThrows(IllegalArgumentException.class, () ->
                new DynatraceProvider("dt01.server_us_abcdef1234.abc123_a1b2c3d4e5"));
        // missing sha1 (no underscore after secret)
        assertThrows(IllegalArgumentException.class, () ->
                new DynatraceProvider("dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d"));
        // sha1 too short (< 10 chars)
        assertThrows(IllegalArgumentException.class, () ->
                new DynatraceProvider("dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_abc12"));
    }

    @Test
    void configOriginTrailingSlashIsNormalised() {
        String expected = "https://cdn.example.com/server/"
                + "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5.json";
        assertEquals(expected, DynatraceProvider.buildCdnUrl(TEST_KEY,
                DynatraceProviderOptions.builder().configOrigin("https://cdn.example.com/").build()));
        assertEquals(expected, DynatraceProvider.buildCdnUrl(TEST_KEY,
                DynatraceProviderOptions.builder().configOrigin("https://cdn.example.com").build()));
    }

    @Test
    void builderRejectsBlankConfigOrigin() {
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder().configOrigin(""));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder().configOrigin(null));
    }

    @Test
    void builderRejectsConfigOriginWithPath() {
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder().configOrigin("https://cdn.example.com/server"));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder().configOrigin("https://cdn.example.com/server/path"));
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
