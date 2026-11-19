package com.dynatrace.openfeature;

import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ProviderState;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DynatraceProviderEnvTest {

    private static final String CODE_KEY =
            "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5";
    private static final String ENV_KEY =
            "dt01.server_us_fedcba4321.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5";

    private final Map<String, String> env = new HashMap<>();
    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private String serverOrigin() {
        return server.url("/").toString().replaceAll("/+$", "");
    }

    private DynatraceProviderOptions resolve(DynatraceProviderOptions options) {
        return DynatraceProviderOptions.withEnvFallback(options, env::get);
    }

    /** Initializes a provider against the mock CDN and returns the path of the first fetch. */
    private String initialFetchPath(String sdkKey) throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(TestFixtures.FLAG_CONFIG));
        env.put("DT_FM_CONFIG_ORIGIN", serverOrigin());
        env.put("DT_FM_SSE_DISABLED", "true");
        DynatraceProvider p = new DynatraceProvider(sdkKey, null, env::get);
        try {
            p.initialize(new ImmutableContext());
            assertEquals(ProviderState.READY, p.getState());
            RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(req);
            return req.getPath();
        } finally {
            p.shutdown();
        }
    }

    @Test
    void sdkKeyFromEnvUsedWhenNotSetInCode() throws Exception {
        env.put("DT_FM_SDK_KEY", ENV_KEY);
        // also covers DT_FM_CONFIG_ORIGIN: the fetch reaches the mock server
        assertEquals("/server/" + ENV_KEY + ".json", initialFetchPath(null));
    }

    @Test
    void sdkKeyFromCodeWinsOverEnv() throws Exception {
        env.put("DT_FM_SDK_KEY", ENV_KEY);
        assertEquals("/server/" + CODE_KEY + ".json", initialFetchPath(CODE_KEY));
    }

    @Test
    void blankSdkKeyInCodeFallsBackToEnv() throws Exception {
        env.put("DT_FM_SDK_KEY", ENV_KEY);
        assertEquals("/server/" + ENV_KEY + ".json", initialFetchPath("  "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void missingSdkKeyFailsNamingEnvVar(String envValue) {
        env.put("DT_FM_SDK_KEY", envValue);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DynatraceProvider(null, null, env::get));
        assertTrue(e.getMessage().contains("DT_FM_SDK_KEY"), e.getMessage());
    }

    @Test
    void malformedSdkKeyFromEnvFailsNamingEnvVar() {
        env.put("DT_FM_SDK_KEY", "dt01.web_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DynatraceProvider(null, null, env::get));
        assertTrue(e.getMessage().contains("DT_FM_SDK_KEY"), e.getMessage());
        assertFalse(e.getMessage().contains("de848e97a9cc4cc78aae568e65f49a9d"), "secret must not leak");
    }

    @Test
    void configOriginFromEnvUsedWhenNotSetInCode() {
        env.put("DT_FM_CONFIG_ORIGIN", " https://cdn.env.example.com/ ");
        assertEquals("https://cdn.env.example.com", resolve(null).getConfigOrigin().toString());
    }

    @Test
    void configOriginFromCodeWinsOverEnv() {
        env.put("DT_FM_CONFIG_ORIGIN", "https://cdn.env.example.com");
        DynatraceProviderOptions options = DynatraceProviderOptions.builder()
                .configOrigin("https://cdn.code.example.com").build();
        assertEquals("https://cdn.code.example.com", resolve(options).getConfigOrigin().toString());
    }

    @Test
    void metricsOriginFromEnvUsedWhenNotSetInCode() {
        env.put("DT_FM_METRICS_ORIGIN", "https://metrics.env.example.com");
        assertEquals("https://metrics.env.example.com/v1/metrics", resolve(null).getMetricsUrl());
    }

    @Test
    void metricsOriginFromCodeWinsOverEnv() {
        env.put("DT_FM_METRICS_ORIGIN", "https://metrics.env.example.com");
        DynatraceProviderOptions options = DynatraceProviderOptions.builder()
                .metricsOrigin("https://metrics.code.example.com").build();
        assertEquals("https://metrics.code.example.com/v1/metrics", resolve(options).getMetricsUrl());
    }

    @ParameterizedTest
    @CsvSource({"true,false", "TRUE,false", " True ,false", "false,true", "False,true"})
    void sseDisabledFromEnvUsedWhenNotSetInCode(String envValue, boolean expectedSseEnabled) {
        env.put("DT_FM_SSE_DISABLED", envValue);
        assertEquals(expectedSseEnabled, resolve(null).isSseEnabled());
    }

    @Test
    void disableSseInCodeWinsOverEnv() {
        env.put("DT_FM_SSE_DISABLED", "false");
        DynatraceProviderOptions options = DynatraceProviderOptions.builder().disableSse().build();
        assertFalse(resolve(options).isSseEnabled());
    }

    @Test
    void emptyEnvValuesAreTreatedAsUnset() {
        env.put("DT_FM_CONFIG_ORIGIN", "");
        env.put("DT_FM_METRICS_ORIGIN", "  ");
        env.put("DT_FM_SSE_DISABLED", " ");
        DynatraceProviderOptions resolved = resolve(null);
        assertNull(resolved.getConfigOrigin());
        assertNull(resolved.getMetricsUrl());
        assertTrue(resolved.isSseEnabled());
    }

    @Test
    void codeOptionsSurviveResolution() {
        DynatraceProviderOptions options = DynatraceProviderOptions.builder()
                .metricsFlushInterval(Duration.ofSeconds(5)).build();
        assertEquals(Duration.ofSeconds(5), resolve(options).getMetricsFlushInterval());
    }

    @ParameterizedTest
    @CsvSource({
            "DT_FM_CONFIG_ORIGIN,https://cdn.example.com/path",
            "DT_FM_CONFIG_ORIGIN,ftp://cdn.example.com",
            "DT_FM_METRICS_ORIGIN,https://metrics.example.com?q=1",
            "DT_FM_SSE_DISABLED,yes",
            "DT_FM_SSE_DISABLED,1",
    })
    void invalidEnvValueFailsConstructionNamingEnvVar(String name, String value) {
        env.put(name, value);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DynatraceProvider(CODE_KEY, null, env::get));
        assertTrue(e.getMessage().contains(name), e.getMessage());
    }
}
