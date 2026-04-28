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

    private static final String TEST_KEY = "dt_server_us_testkey";
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
        DynatraceProviderOptions options = DynatraceProviderOptions.builder("dt_server_us_abc123").build();
        assertEquals("https://cdn.us.fm.dynatrace.com/server/dt_server_us_abc123.json", options.getCdnUrl());
    }

    @Test
    void cdnUrlUsesGeoAndTypeFromKey() {
        DynatraceProviderOptions eu = DynatraceProviderOptions.builder("dt_server_eu_abc123").build();
        assertTrue(eu.getCdnUrl().contains("cdn.eu.fm.dynatrace.com"));
    }

    @Test
    void builderRejectsBlankKey() {
        assertThrows(IllegalArgumentException.class, () -> DynatraceProviderOptions.builder(""));
        assertThrows(IllegalArgumentException.class, () -> DynatraceProviderOptions.builder(null));
        assertThrows(IllegalArgumentException.class, () -> DynatraceProviderOptions.builder("   "));
    }

    @Test
    void builderRejectsInvalidKeyFormat() {
        assertThrows(IllegalArgumentException.class, () -> DynatraceProviderOptions.builder("notavalidkey"));
        assertThrows(IllegalArgumentException.class, () -> DynatraceProviderOptions.builder("dt_server_us"));
    }
}
