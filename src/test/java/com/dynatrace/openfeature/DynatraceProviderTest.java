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
        return new DynatraceProvider(mockCdnUrl(), new FlagdCore(), fetcher,
                Duration.ofMillis(50), gracePeriod);
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
