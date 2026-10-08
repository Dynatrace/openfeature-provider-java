package com.dynatrace.openfeature;

import java.time.Duration;

/**
 * The package-private seam the black-box e2e suite in {@code com.dynatrace.openfeature.e2e}
 * needs. Everything else it does goes through the OpenFeature SDK and the mock server.
 */
public final class ProviderTestSupport {

    private ProviderTestSupport() {
    }

    /** SSE is disabled for the suite, so the disconnected cadence is the only one in play. */
    public static Duration pollInterval() {
        return DynatraceProvider.POLL_INTERVAL_SSE_DISCONNECTED;
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
}
