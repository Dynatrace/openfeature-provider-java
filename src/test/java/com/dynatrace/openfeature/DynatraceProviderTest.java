package com.dynatrace.openfeature;

import dev.openfeature.contrib.tools.flagd.core.FlagdCore;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.*;

class DynatraceProviderTest {

    private static final String TEST_KEY = "dt_server_us_03ee0ef7cd4244bfb8b71fea8f783f53";
    private static final String FLAG_CONFIG = TestFixtures.FLAG_CONFIG;

    private MockWebServer server;
    private HttpClient httpClient;

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

    private DynatraceProvider provider() {
        DynatraceProviderOptions options = DynatraceProviderOptions.builder(TEST_KEY)
                .cdnBaseUrl(server.url("/server/").toString()) // dev override; points to MockWebServer
                .build();
        return new DynatraceProvider(options, new FlagdCore(), httpClient);
    }

    @Test
    void providerNameIsDynatrace() {
        DynatraceProviderOptions options = DynatraceProviderOptions.builder(TEST_KEY).build();
        DynatraceProvider p = new DynatraceProvider(options, new FlagdCore(), httpClient);
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
        server.enqueue(new MockResponse().setResponseCode(503));

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

        // the provider returns FLAG_NOT_FOUND; the SDK (not the provider) applies the default value
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
        // any alphabetic geo is accepted (e.g. de, oc, apac)
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
        // missing dt_ prefix
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("notavalidkey"));
        // not enough segments
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_server_us"));
    }

    @Test
    void builderRejectsNonServerType() {
        // this is a server-side provider; reject web and mobile keys
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_web_us_03ee0ef7cd4244bfb8b71fea8f783f53"));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_mobile_us_03ee0ef7cd4244bfb8b71fea8f783f53"));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_unknown_us_03ee0ef7cd4244bfb8b71fea8f783f53"));
    }

    @Test
    void builderRejectsNonAlphabeticGeo() {
        // dashes, dots, and digits are rejected
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_server_us-east_03ee0ef7cd4244bfb8b71fea8f783f53"));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_server_x.y_03ee0ef7cd4244bfb8b71fea8f783f53"));
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_server_us2_03ee0ef7cd4244bfb8b71fea8f783f53"));
    }

    @Test
    void builderRejectsInvalidIdLength() {
        // too short
        assertThrows(IllegalArgumentException.class, () ->
                DynatraceProviderOptions.builder("dt_server_us_abc123"));
        // too long
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
