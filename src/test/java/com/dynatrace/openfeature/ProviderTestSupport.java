package com.dynatrace.openfeature;

import java.time.Duration;

/**
 * Bridge that lets the black-box acceptance suite in {@code com.dynatrace.openfeature.e2e} reach
 * the handful of package-private details it genuinely needs.
 *
 * <p>The suite drives the provider through the OpenFeature SDK and observes it through the mock
 * server, which is the point. Two things cannot be reached that way:
 *
 * <ul>
 *   <li>the poll cadence and request timeout, which scenarios assert against rather than hard-code,
 *       so the numbers can only ever come from the provider itself;</li>
 *   <li>the SSE-triggered re-fetch, which the single-flight scenario needs in order to occupy the
 *       fetch slot. Opening a real SSE stream would also switch the provider onto the
 *       SSE-connected cadence, changing the timing the scenario is there to measure.</li>
 * </ul>
 */
public final class ProviderTestSupport {

    private ProviderTestSupport() {
    }

    /** The poll cadence the acceptance suite runs at; SSE is disabled, so this is the only one. */
    public static Duration pollInterval() {
        return DynatraceProvider.POLL_INTERVAL_SSE_DISCONNECTED;
    }

    /** Bound on a single CDN request attempt (KB section 2.1). */
    public static Duration requestTimeout() {
        return CdnFetcher.REQUEST_TIMEOUT;
    }

    /**
     * Requests an SSE-triggered re-fetch as {@code SseManager} would, without a live stream.
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
