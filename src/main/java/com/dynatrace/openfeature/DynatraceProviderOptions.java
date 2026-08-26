package com.dynatrace.openfeature;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional configuration for {@link DynatraceProvider}.
 *
 * <p>Build via {@link #builder()}.
 */
public final class DynatraceProviderOptions {

    private static final Logger log = LoggerFactory.getLogger(DynatraceProviderOptions.class);

    /** Default periodic flush interval for the metrics hook. */
    static final Duration DEFAULT_METRICS_FLUSH_INTERVAL = Duration.ofSeconds(30);

    /**
     * Default capacity threshold (distinct flag keys in the live aggregate map) at which the
     * metrics hook triggers an out-of-band flush.
     */
    static final int DEFAULT_METRICS_SOFT_CAP_DISTINCT_FLAGS = 1_000;

    /**
     * Default hard cap for the aggregate map. Once the live map holds this many distinct flag
     * keys, evaluations of further new flags are dropped (existing flags keep accumulating).
     */
    static final int DEFAULT_METRICS_HARD_CAP_DISTINCT_FLAGS = 2_000;

    private final URI configOrigin;
    private final String metricsUrl;
    private final Duration metricsFlushInterval;
    private final int metricsSoftCapDistinctFlags;
    private final int metricsHardCapDistinctFlags;
    private final boolean sseEnabled;

    private DynatraceProviderOptions(Builder builder) {
        this.configOrigin = builder.configOrigin;
        this.metricsUrl = builder.metricsUrl;
        this.metricsFlushInterval = builder.metricsFlushInterval;
        this.metricsSoftCapDistinctFlags = DEFAULT_METRICS_SOFT_CAP_DISTINCT_FLAGS;
        this.metricsHardCapDistinctFlags = DEFAULT_METRICS_HARD_CAP_DISTINCT_FLAGS;
        this.sseEnabled = builder.sseEnabled;
    }

    URI getConfigOrigin() {
        return configOrigin;
    }

    String getMetricsUrl() {
        return metricsUrl;
    }

    /** Periodic flush interval for the evaluation-metrics hook. */
    public Duration getMetricsFlushInterval() {
        return metricsFlushInterval;
    }

    /**
     * Soft capacity threshold (number of distinct flag keys in the live aggregate map) at which
     * the metrics hook triggers an out-of-band flush.
     */
    public int getMetricsSoftCapDistinctFlags() {
        return metricsSoftCapDistinctFlags;
    }

    /**
     * Hard capacity for the aggregate map. Once the live map holds this many distinct flag
     * keys, evaluations of further new flags are dropped (existing flags keep accumulating).
     */
    public int getMetricsHardCapDistinctFlags() {
        return metricsHardCapDistinctFlags;
    }

    public boolean isSseEnabled() {
        return sseEnabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        URI configOrigin = null;
        String metricsUrl = null;
        Duration metricsFlushInterval = DEFAULT_METRICS_FLUSH_INTERVAL;
        boolean sseEnabled = true;

        /**
         * Override the config origin (scheme, host, and port — no path).
         * For dev/labs environments (e.g. {@code https://cdn.eu.fm.dynatracelabs.com}).
         * Production URL is derived automatically from the SDK key.
         */
        public Builder configOrigin(String configOrigin) {
            if (configOrigin == null || configOrigin.isBlank()) {
                throw new IllegalArgumentException("configOrigin must not be null or blank");
            }
            String stripped = configOrigin.strip();
            this.configOrigin = normaliseConfigOrigin(stripped);
            return this;
        }

        /**
         * Validates that {@code value} is a bare origin and returns it in canonical form.
         *
         * <p>An origin is a scheme, host, and optional port — nothing else. Rebuilding from the
         * parsed parts (rather than trimming the raw string) keeps a query or fragment from
         * silently corrupting the platform and key segments appended later, which would
         * surface only as a 404 on the first config fetch.
         */
        private static URI normaliseConfigOrigin(String value) {
            URI uri;
            try {
                uri = new URI(value);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("configOrigin is not a valid URI: " + e.getMessage());
            }
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException(
                        "configOrigin must use http or https; got: \"" + value + "\"");
            }
            // getHost() is null for a malformed authority — an empty host, or a non-numeric port
            // that makes the authority registry-based rather than server-based.
            if (uri.getHost() == null) {
                throw new IllegalArgumentException(
                        "configOrigin must include a valid host; got: \"" + value + "\"");
            }
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !path.replaceAll("/+$", "").isEmpty()) {
                throw new IllegalArgumentException(
                        "configOrigin must not include a path; got: \"" + value + "\"");
            }
            if (uri.getQuery() != null) {
                throw new IllegalArgumentException(
                        "configOrigin must not include a query string; got: \"" + value + "\"");
            }
            if (uri.getFragment() != null) {
                throw new IllegalArgumentException(
                        "configOrigin must not include a fragment; got: \"" + value + "\"");
            }
            if (uri.getUserInfo() != null) {
                throw new IllegalArgumentException(
                        "configOrigin must not include user info; got: \"" + value + "\"");
            }
            // The multi-arg constructor assembles the authority itself, including the brackets
            // around an IPv6 host.
            try {
                return new URI(scheme.toLowerCase(Locale.ROOT), null,
                        uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), null, null, null);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("configOrigin is not a valid URI: " + e.getMessage());
            }
        }

        /**
         * Override the metrics ingest endpoint URL.
         */
        public Builder metricsUrl(String metricsUrl) {
            if (metricsUrl == null || metricsUrl.isBlank()) {
                throw new IllegalArgumentException("metricsUrl must not be null or blank");
            }
            this.metricsUrl = metricsUrl.strip();
            return this;
        }

        /** Periodic flush interval for the metrics hook. Falls back to the default if invalid. */
        public Builder metricsFlushInterval(Duration interval) {
            if (interval == null || interval.isZero() || interval.isNegative() || interval.toMillis() <= 0) {
                log.warn("metricsFlushInterval is invalid, using default value");
                this.metricsFlushInterval = DEFAULT_METRICS_FLUSH_INTERVAL;
                return this;
            }
            this.metricsFlushInterval = interval;
            return this;
        }

        /** Disables SSE; provider will poll only. Polling interval stays 10s. */
        public Builder disableSse() {
            this.sseEnabled = false;
            return this;
        }

        public DynatraceProviderOptions build() {
            return new DynatraceProviderOptions(this);
        }
    }
}
