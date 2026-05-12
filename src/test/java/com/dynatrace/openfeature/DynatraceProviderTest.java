package com.dynatrace.openfeature;

import dev.openfeature.contrib.tools.flagd.core.FlagdCore;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderState;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DynatraceProviderTest {

    private static final String TEST_KEY = "dt_server_us_03ee0ef7cd4244bfb8b71fea8f783f53";
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

    @Test
    void providerNameIsDynatrace() {
        DynatraceProvider p = provider();
        assertEquals("dynatrace", p.getMetadata().getName());
        p.shutdown();
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
                DynatraceProviderOptions.builder("dt_server_us_03ee0ef7cd4244bfb8b71fea8f783f53").build();
        assertEquals(
                "https://cdn.us.fm.dynatrace.com/server/dt_server_us_03ee0ef7cd4244bfb8b71fea8f783f53.json",
                options.getCdnUrl());
    }

    @Test
    void cdnUrlUsesGeoFromKey() {
        DynatraceProviderOptions eu =
                DynatraceProviderOptions.builder("dt_server_eu_03ee0ef7cd4244bfb8b71fea8f783f53").build();
        assertTrue(eu.getCdnUrl().contains("cdn.eu.fm.dynatrace.com/server/"));
    }

    @Test
    void builderAcceptsArbitraryAlphabeticGeo() {
        DynatraceProviderOptions de =
                DynatraceProviderOptions.builder("dt_server_de_03ee0ef7cd4244bfb8b71fea8f783f53").build();
        assertTrue(de.getCdnUrl().contains("cdn.de.fm.dynatrace.com"));
    }

    @Test
    void builderTrimsWhitespaceFromKey() {
        DynatraceProviderOptions options = DynatraceProviderOptions
                .builder("  dt_server_us_03ee0ef7cd4244bfb8b71fea8f783f53  ").build();
        assertEquals("dt_server_us_03ee0ef7cd4244bfb8b71fea8f783f53", options.getKey());
    }

    @Test
    void builderRejectsBlankKey() {
        assertThrows(IllegalArgumentException.class, () -> DynatraceProviderOptions.builder(""));
        assertThrows(IllegalArgumentException.class, () -> DynatraceProviderOptions.builder(null));
        assertThrows(IllegalArgumentException.class, () -> DynatraceProviderOptions.builder("   "));
    }

    @Test
    void builderRejectsInvalidKeyFormat() {
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("notavalidkey"));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_server_us"));
    }

    @Test
    void builderRejectsNonServerType() {
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_web_us_03ee0ef7cd4244bfb8b71fea8f783f53"));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_mobile_us_03ee0ef7cd4244bfb8b71fea8f783f53"));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_unknown_us_03ee0ef7cd4244bfb8b71fea8f783f53"));
    }

    @Test
    void builderRejectsNonAlphabeticGeo() {
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_server_us-east_03ee0ef7cd4244bfb8b71fea8f783f53"));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_server_x.y_03ee0ef7cd4244bfb8b71fea8f783f53"));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_server_us2_03ee0ef7cd4244bfb8b71fea8f783f53"));
    }

    @Test
    void builderRejectsInvalidIdLength() {
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_server_us_abc123"));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_server_us_03ee0ef7cd4244bfb8b71fea8f783f53extra"));
    }

    @Test
    void cdnBaseUrlWithoutTrailingSlashIsAutoAppended() {
        DynatraceProviderOptions options = DynatraceProviderOptions.builder(TEST_KEY)
                .cdnBaseUrl("https://cdn.example.com/server")
                .build();
        assertEquals(
                "https://cdn.example.com/server/dt_server_us_03ee0ef7cd4244bfb8b71fea8f783f53.json",
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
