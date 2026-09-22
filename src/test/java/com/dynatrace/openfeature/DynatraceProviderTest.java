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
import okhttp3.mockwebserver.Dispatcher;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** short SSE-disconnect debounce so escalation is observable quickly in tests */
    private static final Duration TEST_SSE_DEBOUNCE = Duration.ofMillis(100);

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
                Duration.ofMillis(50), gracePeriod, TEST_SSE_DEBOUNCE);
    }

    /** Polls {@code provider.getState()} until it equals {@code expected} or the timeout elapses. */
    private static void awaitState(DynatraceProvider provider, ProviderState expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (provider.getState() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(expected, provider.getState(), "state did not reach " + expected + " within " + timeoutMs + "ms");
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
                    .metricsOrigin(metricsServer.url("/").toString().replaceAll("/+$", ""))
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
                    .metricsOrigin(metricsServer.url("/").toString().replaceAll("/+$", ""))
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
                    .metricsOrigin(metricsServer.url("/").toString().replaceAll("/+$", ""))
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
    void metricsOriginOverrideAppendsMetricsPath() {
        DynatraceProviderOptions options = DynatraceProviderOptions.builder()
                .metricsOrigin("https://metrics.example.com")
                .build();
        assertEquals("https://metrics.example.com/v1/metrics",
                DynatraceProvider.buildMetricsUrl(TEST_KEY, options));
        assertEquals("https://metrics.example.com/v1/metrics",
                DynatraceProvider.buildMetricsUrl(TEST_KEY,
                        DynatraceProviderOptions.builder().metricsOrigin("https://metrics.example.com/").build()));
        assertEquals("https://metrics.example.com:443/v1/metrics",
                DynatraceProvider.buildMetricsUrl(TEST_KEY,
                        DynatraceProviderOptions.builder().metricsOrigin("https://metrics.example.com:443").build()));
    }

    @Test
    void metricsOriginOverrideTrimsWhitespace() {
        DynatraceProviderOptions options = DynatraceProviderOptions.builder()
                .metricsOrigin("  https://metrics.example.com  ")
                .build();
        assertEquals("https://metrics.example.com/v1/metrics",
                DynatraceProvider.buildMetricsUrl(TEST_KEY, options));
    }

    @Test
    void builderRejectsBlankMetricsOrigin() {
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder().metricsOrigin(""));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder().metricsOrigin(null));
    }

    @Test
    void builderRejectsMetricsOriginWithPath() {
        for (String origin : new String[] {
                "https://metrics.example.com/v1/metrics",
                "https://metrics.example.com//",
                "https://metrics.example.com/..",
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                    DynatraceProviderOptions.builder().metricsOrigin(origin), origin);
        }
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
        assertEquals("https://cdn.example.com:443/server/"
                        + "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5.json",
                DynatraceProvider.buildCdnUrl(TEST_KEY,
                        DynatraceProviderOptions.builder().configOrigin("https://cdn.example.com:443").build()));
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
        for (String origin : new String[] {
                "https://cdn.example.com/server",
                "https://cdn.example.com//",
                "https://cdn.example.com/..",
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                    DynatraceProviderOptions.builder().configOrigin(origin), origin);
        }
    }

    @Test
    void configOriginAcceptsUnderscoreHost() {
        String expected = "http://my_service:8080/server/"
                + "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5.json";
        assertEquals(expected, DynatraceProvider.buildCdnUrl(TEST_KEY,
                DynatraceProviderOptions.builder().configOrigin("http://my_service:8080").build()));
    }

    @Test
    void shutdownBeforeInitializeIsSafe() {
        DynatraceProvider p = provider();
        assertDoesNotThrow(p::shutdown);
    }

    // -- SSE behavior tests --

    @Test
    void extractSseUrl_returnsUrlForSseType() {
        String config = "{\"flags\":{},\"eventStreams\":[{\"type\":\"sse\",\"url\":\"https://realtime.ably.io/event-stream?key=abc&channels=xyz\",\"inactivityDelaySec\":120}]}";
        assertEquals("https://realtime.ably.io/event-stream?key=abc&channels=xyz",
                DynatraceProvider.extractSseUrl(config));
    }

    @Test
    void extractSseUrl_returnsNullWhenNoEventStreams() {
        assertEquals(null, DynatraceProvider.extractSseUrl("{\"flags\":{}}"));
    }

    @Test
    void extractSseUrl_returnsNullWhenNoSseTypeEntry() {
        String config = "{\"flags\":{},\"eventStreams\":[{\"type\":\"other\",\"url\":\"https://example.com\"}]}";
        assertNull(DynatraceProvider.extractSseUrl(config));
    }

    @Test
    void extractSseUrl_skipsNonSseEntriesAndFindsFirst() {
        String config = "{\"flags\":{},\"eventStreams\":["
                + "{\"type\":\"webhook\",\"url\":\"https://hook.example.com\"},"
                + "{\"type\":\"sse\",\"url\":\"https://realtime.ably.io/sse\"}"
                + "]}";
        assertEquals("https://realtime.ably.io/sse", DynatraceProvider.extractSseUrl(config));
    }

    @Test
    void extractSseUrl_returnsNullOnMalformedJson() {
        assertNull(DynatraceProvider.extractSseUrl("not-json"));
    }

    @Test
    void extractSseUrl_endpointWithExplicitOrigin() {
        String config = "{\"flags\":{},\"eventStreams\":[{\"type\":\"sse\",\"endpoint\":"
                + "{\"origin\":\"https://sse.example.com\",\"requestUri\":\"/stream?ch=abc\"}}]}";
        assertEquals("https://sse.example.com/stream?ch=abc",
                DynatraceProvider.extractSseUrl(config, "https://cdn.ignored.com"));
    }

    @Test
    void extractSseUrl_endpointWithoutOriginUsesFallback() {
        String config = "{\"flags\":{},\"eventStreams\":[{\"type\":\"sse\",\"endpoint\":"
                + "{\"requestUri\":\"/stream?ch=abc\"}}]}";
        assertEquals("https://cdn.fallback.com/stream?ch=abc",
                DynatraceProvider.extractSseUrl(config, "https://cdn.fallback.com"));
    }

    @Test
    void extractSseUrl_endpointWithoutOriginAndNoFallbackReturnsNull() {
        String config = "{\"flags\":{},\"eventStreams\":[{\"type\":\"sse\",\"endpoint\":"
                + "{\"requestUri\":\"/stream?ch=abc\"}}]}";
        assertNull(DynatraceProvider.extractSseUrl(config, null));
    }

    @Test
    void extractSseUrl_directUrlTakesPrecedenceOverEndpoint() {
        String config = "{\"flags\":{},\"eventStreams\":[{\"type\":\"sse\","
                + "\"url\":\"https://direct.example.com/sse\","
                + "\"endpoint\":{\"origin\":\"https://other.example.com\",\"requestUri\":\"/stream\"}}]}";
        assertEquals("https://direct.example.com/sse",
                DynatraceProvider.extractSseUrl(config, "https://cdn.example.com"));
    }

    @Test
    void sseEnabledByDefault() {
        DynatraceProviderOptions opts = DynatraceProviderOptions.builder().build();
        assertTrue(opts.isSseEnabled());
    }

    @Test
    void disableSseOptionWorks() {
        DynatraceProviderOptions opts = DynatraceProviderOptions.builder()
                .disableSse()
                .build();
        assertFalse(opts.isSseEnabled());
    }

    @Test
    void pollFailureStaysReadyWhenSseConnected() throws Exception {
        // init succeeds
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));
        // subsequent polls fail
        for (int i = 0; i < RetryStrategy.MAX_ATTEMPTS * 5; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }

        DynatraceProvider p = fastProvider(Duration.ofSeconds(10));
        p.initialize(new ImmutableContext());

        // simulate SSE connection — provider should stay READY despite poll failures
        p.onSseConnected();

        Thread.sleep(300); // allow polls to fire and fail

        assertEquals(ProviderState.READY, p.getState());
        p.shutdown();
    }

    @Test
    void sseDisconnectTransitionsToStale() throws Exception {
        // Long poll interval so the only fetches are SSE-triggered, not the background poller.
        // Disconnect is debounced, so STALE arrives shortly after the debounce window elapses.
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));
        // absorb the immediate fetch fired on connect and on disconnect escalation
        server.enqueue(new MockResponse().setResponseCode(304));
        server.enqueue(new MockResponse().setResponseCode(304));

        DynatraceProvider p = new DynatraceProvider(
                TEST_KEY, mockCdnUrl(), new FlagdCore(),
                new CdnFetcher(mockCdnUrl(), httpClient, NO_DELAY_RETRY),
                Duration.ofSeconds(30), Duration.ofSeconds(30), TEST_SSE_DEBOUNCE);
        p.initialize(new ImmutableContext());
        p.onSseConnected();

        assertEquals(ProviderState.READY, p.getState());

        // a sustained disconnect (debounce elapses with no reconnect) escalates to STALE
        p.onSseDisconnected();
        awaitState(p, ProviderState.STALE, 2_000);

        p.shutdown();
    }

    @Test
    void sseBlipReconnectDoesNotGoStale() throws Exception {
        // A fault immediately followed by a reconnect (within the debounce window) is a blip:
        // the provider must stay READY and never emit STALE.
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));
        server.enqueue(new MockResponse().setResponseCode(304)); // connect-triggered fetch
        server.enqueue(new MockResponse().setResponseCode(304)); // reconnect-triggered fetch

        DynatraceProvider p = new DynatraceProvider(
                TEST_KEY, mockCdnUrl(), new FlagdCore(),
                new CdnFetcher(mockCdnUrl(), httpClient, NO_DELAY_RETRY),
                Duration.ofSeconds(30), Duration.ofSeconds(30), TEST_SSE_DEBOUNCE);
        p.initialize(new ImmutableContext());
        p.onSseConnected();

        // fault then reconnect before the debounce fires
        p.onSseDisconnected();
        p.onSseConnected();

        // wait past the debounce window; state must remain READY (no escalation happened)
        Thread.sleep(TEST_SSE_DEBOUNCE.toMillis() + 200);
        assertEquals(ProviderState.READY, p.getState());

        p.shutdown();
    }

    @Test
    void sseDisconnectDeduplicatesMultipleCalls() throws Exception {
        // long poll interval: no background recovery can interfere with the assertion
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));
        server.enqueue(new MockResponse().setResponseCode(304));
        server.enqueue(new MockResponse().setResponseCode(304));

        DynatraceProvider p = new DynatraceProvider(
                TEST_KEY, mockCdnUrl(), new FlagdCore(),
                new CdnFetcher(mockCdnUrl(), httpClient, NO_DELAY_RETRY),
                Duration.ofSeconds(30), Duration.ofSeconds(30), TEST_SSE_DEBOUNCE);
        p.initialize(new ImmutableContext());
        p.onSseConnected();

        // repeated fault callbacks (the event source fires these on every blip) must not throw and
        // must collapse to a single escalation
        assertDoesNotThrow(p::onSseDisconnected);
        assertDoesNotThrow(p::onSseDisconnected);
        assertDoesNotThrow(p::onSseDisconnected);
        awaitState(p, ProviderState.STALE, 2_000);

        p.shutdown();
    }

    @Test
    void sseDisconnectGoesStaleThenRecoversThroughPoll() throws Exception {
        // First request serves the config; afterwards the CDN's health is toggled by cdnFailing,
        // so STALE persists while it fails and the fast poll cadence recovers once it is healthy.
        AtomicBoolean cdnFailing = new AtomicBoolean(false);
        server.setDispatcher(new Dispatcher() {
            private boolean served;
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!served) {
                    served = true;
                    return new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG);
                }
                return cdnFailing.get()
                        ? new MockResponse().setResponseCode(503)
                        : new MockResponse().setResponseCode(304);
            }
        });

        DynatraceProvider p = fastProvider(Duration.ofSeconds(10));
        p.initialize(new ImmutableContext());
        p.onSseConnected();

        // CDN starts failing, then SSE drops: the debounced disconnect escalates to STALE and the
        // failing fast polls keep it there.
        cdnFailing.set(true);
        p.onSseDisconnected();
        awaitState(p, ProviderState.STALE, 2_000);

        // CDN healthy again: the fast poll cadence recovers to READY
        cdnFailing.set(false);
        awaitState(p, ProviderState.READY, 2_000);
        p.shutdown();
    }

    @Test
    void sseConnectTriggersImmediateFetch() throws Exception {
        // long poll interval so the only fetch after init is the one triggered by connect
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));
        server.enqueue(new MockResponse().setResponseCode(304));

        DynatraceProvider p = new DynatraceProvider(
                TEST_KEY, mockCdnUrl(), new FlagdCore(),
                new CdnFetcher(mockCdnUrl(), httpClient, NO_DELAY_RETRY),
                Duration.ofSeconds(60), Duration.ofSeconds(60), TEST_SSE_DEBOUNCE);
        p.initialize(new ImmutableContext());
        server.takeRequest(); // initial fetch

        p.onSseConnected();

        assertNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS),
                "SSE connect should trigger an immediate re-fetch to catch missed changes");
        p.shutdown();
    }

    @Test
    void pollCadenceSlowsWhenSseConnectedAndSpeedsUpOnDisconnect() throws Exception {
        // Assert the consequence of the SSE transitions (not just the state change): the poll
        // cadence is the slow safety-net interval while connected and the fast interval once
        // disconnect escalates. We count fetches over equal windows in each mode.
        AtomicInteger fetches = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            private boolean served;
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!served) {
                    served = true;
                    return new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG);
                }
                fetches.incrementAndGet();
                return new MockResponse().setResponseCode(304);
            }
        });

        Duration connected = Duration.ofSeconds(5);    // slow safety net
        Duration disconnected = Duration.ofMillis(50);  // aggressive
        DynatraceProvider p = new DynatraceProvider(
                TEST_KEY, mockCdnUrl(), new FlagdCore(),
                new CdnFetcher(mockCdnUrl(), httpClient, NO_DELAY_RETRY),
                connected, disconnected, Duration.ofSeconds(30), TEST_SSE_DEBOUNCE);
        p.initialize(new ImmutableContext());

        // connected → slow cadence: no periodic poll should fire in a 300ms window
        p.onSseConnected();
        Thread.sleep(100); // let the interval switch (and the connect-triggered fetch) settle
        fetches.set(0);
        Thread.sleep(300);
        int whileConnected = fetches.get();

        // disconnected → after debounce, fast cadence: many polls in the same window
        p.onSseDisconnected();
        Thread.sleep(TEST_SSE_DEBOUNCE.toMillis() + 100); // let escalation/interval switch settle
        fetches.set(0);
        Thread.sleep(300);
        int whileDisconnected = fetches.get();

        assertTrue(whileConnected <= 1,
                "slow safety-net cadence should not poll repeatedly while connected, saw " + whileConnected);
        assertTrue(whileDisconnected >= 3,
                "fast cadence should poll repeatedly after disconnect, saw " + whileDisconnected);
        assertTrue(whileDisconnected > whileConnected, "cadence should speed up after SSE disconnect");
        p.shutdown();
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

    // -- SSE message handling tests --

    @Test
    void sseMessageWithRefetchConfigTriggersImmediateRefetch() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG)
                .addHeader("ETag", "\"v1\""));
        server.enqueue(new MockResponse().setResponseCode(304));

        DynatraceProvider p = new DynatraceProvider(
                TEST_KEY, mockCdnUrl(), new FlagdCore(),
                new CdnFetcher(mockCdnUrl(), httpClient, NO_DELAY_RETRY),
                Duration.ofSeconds(60), Duration.ofSeconds(60));
        p.initialize(new ImmutableContext());
        server.takeRequest(); // initial fetch

        p.onSseMessage(new SseMessage("refetchConfig", "\"abc123\"", 1771622898L));

        okhttp3.mockwebserver.RecordedRequest triggered =
                server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(triggered, "expected a triggered re-fetch");

        p.shutdown();
    }

    @Test
    void sseMessageWithUnknownTypeIsIgnored() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));

        DynatraceProvider p = new DynatraceProvider(
                TEST_KEY, mockCdnUrl(), new FlagdCore(),
                new CdnFetcher(mockCdnUrl(), httpClient, NO_DELAY_RETRY),
                Duration.ofSeconds(60), Duration.ofSeconds(60));
        p.initialize(new ImmutableContext());
        server.takeRequest();

        p.onSseMessage(new SseMessage("refetchEvaluation", "\"x\"", 1L));


        okhttp3.mockwebserver.RecordedRequest extra =
                server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertNull(extra, "unknown SSE message types must not trigger a fetch");

        p.shutdown();
    }

    @Test
    void sseMessageTriggersFetchRegardlessOfLastModifiedOrder() throws Exception {
        // The provider no longer tracks/compares lastModified: every refetchConfig poke triggers a
        // conditional GET. Ordering/dedup is delegated to CdnFetcher's Last-Modified guard, which
        // collapses a redundant or out-of-order poke to a cheap 304.
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));
        server.enqueue(new MockResponse().setResponseCode(304));
        server.enqueue(new MockResponse().setResponseCode(304));
        server.enqueue(new MockResponse().setResponseCode(304));

        DynatraceProvider p = new DynatraceProvider(
                TEST_KEY, mockCdnUrl(), new FlagdCore(),
                new CdnFetcher(mockCdnUrl(), httpClient, NO_DELAY_RETRY),
                Duration.ofSeconds(60), Duration.ofSeconds(60));
        p.initialize(new ImmutableContext());
        server.takeRequest(); // initial fetch

        p.onSseMessage(new SseMessage("refetchConfig", "\"new\"", 200L));
        assertNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS),
                "a refetchConfig message should trigger a fetch");

        // an older lastModified still triggers a fetch (the fetch is what enforces ordering, not us)
        p.onSseMessage(new SseMessage("refetchConfig", "\"old\"", 100L));
        assertNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS),
                "an out-of-order message should still trigger a (conditional) fetch");

        // a duplicate lastModified likewise triggers a fetch
        p.onSseMessage(new SseMessage("refetchConfig", "\"dup\"", 200L));
        assertNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS),
                "a duplicate message should still trigger a (conditional) fetch");

        p.shutdown();
    }

    @Test
    void sseMessageNullLastModifiedAlwaysTriggersRefetch() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG));
        server.enqueue(new MockResponse().setResponseCode(304));
        server.enqueue(new MockResponse().setResponseCode(304));

        DynatraceProvider p = new DynatraceProvider(
                TEST_KEY, mockCdnUrl(), new FlagdCore(),
                new CdnFetcher(mockCdnUrl(), httpClient, NO_DELAY_RETRY),
                Duration.ofSeconds(60), Duration.ofSeconds(60));
        p.initialize(new ImmutableContext());
        server.takeRequest();

        p.onSseMessage(new SseMessage("refetchConfig", null, 100L));
        assertNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS),
                "message with lastModified should trigger a fetch");

        p.onSseMessage(new SseMessage("refetchConfig", null, null));
        assertNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS),
                "message with null lastModified must always trigger a fetch");

        p.shutdown();
    }

    @Test
    void sseMessageParsing() {
        SseMessage msg = SseMessage.parse(
                "{\"type\":\"refetchConfig\",\"etag\":\"\\\"abc\\\"\",\"lastModified\":1771622898}");
        assertNotNull(msg);
        assertEquals("refetchConfig", msg.type);
        assertEquals("\"abc\"", msg.etag);
        assertEquals(1771622898L, msg.lastModified);
    }

    @Test
    void sseMessageParsingTolerantToMissingOptionalFields() {
        SseMessage msg = SseMessage.parse("{\"type\":\"refetchConfig\"}");
        assertNotNull(msg);
        assertEquals("refetchConfig", msg.type);
        assertNull(msg.etag);
        assertNull(msg.lastModified);
    }

    @Test
    void sseMessageParsingRejectsUnparseableAndMissingType() {
        assertNull(SseMessage.parse(null));
        assertNull(SseMessage.parse(""));
        assertNull(SseMessage.parse("not-json"));
        assertNull(SseMessage.parse("{\"lastModified\":1}"));
    }

    /** provider whose recovery polling is slow enough not to race assertions about init. */
    private DynatraceProvider slowPollProvider() {
        CdnFetcher fetcher = new CdnFetcher(mockCdnUrl(), httpClient, NO_DELAY_RETRY);
        return new DynatraceProvider(TEST_KEY, mockCdnUrl(), new FlagdCore(), fetcher,
                Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofMillis(200), TEST_SSE_DEBOUNCE);
    }

    @Test
    void initialFetchGivesUpAfterTheBoundedAttemptCount() throws IOException {
        // KB 2.4: the 2-attempt cap applies to the initial fetch too; a failing init gives up
        // in seconds rather than retrying for half a minute.
        for (int i = 0; i < RetryStrategy.MAX_ATTEMPTS; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }

        DynatraceProvider p = slowPollProvider();

        assertThrows(RuntimeException.class, () -> p.initialize(new ImmutableContext()));
        assertEquals(RetryStrategy.MAX_ATTEMPTS, server.getRequestCount(),
                "initial fetch should make exactly " + RetryStrategy.MAX_ATTEMPTS + " attempts");
        p.shutdown();
    }

    @Test
    void failedInitStillStartsPollingAndRecovers() throws Exception {
        // KB 2.4: a failed init recovers on the polling timer like any other failure. Without
        // one, a transient 5xx at startup would wedge the provider in ERROR until restart.
        AtomicInteger requests = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                // fail the whole initial fetch (both attempts), then serve a good config
                if (requests.getAndIncrement() < RetryStrategy.MAX_ATTEMPTS) {
                    return new MockResponse().setResponseCode(503);
                }
                return new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG);
            }
        });

        DynatraceProvider p = fastProvider(Duration.ofSeconds(10));

        assertThrows(RuntimeException.class, () -> p.initialize(new ImmutableContext()),
                "a failed initial fetch should still surface to the caller");
        assertEquals(ProviderState.ERROR, p.getState());

        awaitState(p, ProviderState.READY, 5_000);
        p.shutdown();
    }

    @Test
    void failedInitStartsSseOnceTheFirstPollSucceeds() throws Exception {
        // Init failure means no config, so no SSE URL. The provider must not stay polling-only
        // forever: the first successful poll carries the config that names the SSE endpoint.
        AtomicInteger requests = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().contains("/sse")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "text/event-stream")
                            .setBody(": connected\n\n");
                }
                if (requests.getAndIncrement() < RetryStrategy.MAX_ATTEMPTS) {
                    return new MockResponse().setResponseCode(503);
                }
                return new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG);
            }
        });

        DynatraceProvider p = fastProvider(Duration.ofSeconds(10));
        assertThrows(RuntimeException.class, () -> p.initialize(new ImmutableContext()));

        awaitState(p, ProviderState.READY, 5_000);
        p.shutdown();
    }

    @Test
    void skippedPollTicksDoNotResetTheGraceTimer() throws Exception {
        // KB 2.1.1: a skipped tick emits nothing. Reported as a success it would cancel grace
        // on every tick, holding the provider in READY for a whole CDN outage.
        //
        // Each failing response is delayed past the 50ms poll cadence, so most ticks contend.
        AtomicInteger requests = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (requests.getAndIncrement() == 0) {
                    return new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG);
                }
                return new MockResponse()
                        .setResponseCode(503)
                        .setHeadersDelay(120, TimeUnit.MILLISECONDS);
            }
        });

        DynatraceProvider p = fastProvider(Duration.ofMillis(200));
        p.initialize(new ImmutableContext());

        // SSE never connects, so the first real failure goes STALE and starts the grace timer;
        // the skipped ticks in between must not keep cancelling it.
        awaitState(p, ProviderState.ERROR, 5_000);
        p.shutdown();
    }

    @Test
    void contendedTicksDoNotPileUpRequests() throws Exception {
        // Single-flight: with responses slower than the cadence, the request count must track
        // fetch duration, not tick count.
        AtomicInteger requests = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (requests.getAndIncrement() == 0) {
                    return new MockResponse().setResponseCode(200).setBody(FLAG_CONFIG);
                }
                return new MockResponse()
                        .setResponseCode(304)
                        .setHeadersDelay(150, TimeUnit.MILLISECONDS);
            }
        });

        DynatraceProvider p = fastProvider(Duration.ofSeconds(10));
        p.initialize(new ImmutableContext());

        Thread.sleep(600);
        p.shutdown();

        // ~12 ticks fit in 600ms at a 50ms cadence; at 150ms per fetch only ~4 can actually run.
        int actual = requests.get();
        assertTrue(actual <= 7,
                "expected contended ticks to be skipped, but " + actual + " requests were made");
    }
}
