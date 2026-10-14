package com.dynatrace.openfeature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;

/**
 * The package-private seam the black-box e2e suite in {@code com.dynatrace.openfeature.e2e}
 * needs. Everything else it does goes through the OpenFeature SDK and the mock server.
 */
public final class ProviderTestSupport {

    /** Shared across providers by the test harness, put on the classpath from its gherkin/ dir. */
    private static final String TIMINGS_RESOURCE = "/gherkin/timings.json";
    private static final JsonNode TIMINGS = loadTimings();

    public static final Duration SSE_CONNECTED_POLL_INTERVAL = timing("SSE_CONNECTED_POLL_INTERVAL_MS");
    public static final Duration GRACE_PERIOD = timing("GRACE_PERIOD_MS");
    /** The cadence while SSE is disabled or disconnected; the CDN spec runs with SSE disabled. */
    public static final Duration SSE_DISCONNECTED_POLL_INTERVAL = DynatraceProvider.POLL_INTERVAL_SSE_DISCONNECTED;
    public static final Duration DISCONNECT_DEBOUNCE_TIME_WINDOW = DynatraceProvider.SSE_DISCONNECT_DEBOUNCE;

    private ProviderTestSupport() {
    }

    /** Builds a provider with the harness's shortened SSE-connected poll interval and grace period. */
    public static DynatraceProvider createProvider(String sdkKey, DynatraceProviderOptions options) {
        return new DynatraceProvider(sdkKey, options, SSE_CONNECTED_POLL_INTERVAL, GRACE_PERIOD);
    }

    /** Bound on a single CDN request attempt (KB section 2.1). */
    public static Duration requestTimeout() {
        return CdnFetcher.REQUEST_TIMEOUT;
    }

    /**
     * Requests an SSE-triggered re-fetch as {@code SseManager} would, without a live stream.
     * Connecting a real one would switch the provider onto the SSE-connected cadence, changing the
     * timing the single-flight scenario exists to measure.
     *
     * @param announcedLastModified epoch seconds announced by the notification, or null when the
     *                              announcement carried none
     */
    public static void triggerSseRefetch(DynatraceProvider provider, Long announcedLastModified) {
        PollingManager pm = provider.pollingManager();
        if (pm == null) {
            throw new IllegalStateException("provider has not been initialized; no polling manager");
        }
        pm.triggerSseRefetch(announcedLastModified);
    }

    private static JsonNode loadTimings() {
        try (InputStream in = ProviderTestSupport.class.getResourceAsStream(TIMINGS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + TIMINGS_RESOURCE + " on the test classpath;"
                        + " run `git submodule update --init fm-provider-test-harness`");
            }
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + TIMINGS_RESOURCE, e);
        }
    }

    private static Duration timing(String key) {
        JsonNode value = TIMINGS.get(key);
        if (value == null || !value.canConvertToLong()) {
            throw new IllegalStateException(TIMINGS_RESOURCE + " has no numeric " + key);
        }
        return Duration.ofMillis(value.asLong());
    }
}
