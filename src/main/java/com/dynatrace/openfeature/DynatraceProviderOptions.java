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

    /** Resolved ingest URL ending in {@code /v1/metrics}, or {@code null} to derive from the SDK key. */
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

    /**
     * Validates that {@code value} is a bare origin and returns it in canonical form.
     *
     * <p>An origin is a scheme, host, and optional port — nothing else. Rebuilding from the
     * parsed parts (rather than trimming the raw string) keeps a query or fragment from
     * silently corrupting appended path segments, which would surface only as a 404 on
     * the first request.
     */
    private static URI parseOrigin(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        String stripped = value.strip();
        URI uri;
        try {
            uri = new URI(stripped);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(fieldName + " is not a valid URI: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(
                    fieldName + " must use http or https; got: \"" + stripped + "\"");
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new IllegalArgumentException(
                    fieldName + " must not include a path; got: \"" + stripped + "\"");
        }
        if (uri.getQuery() != null) {
            throw new IllegalArgumentException(
                    fieldName + " must not include a query string; got: \"" + stripped + "\"");
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    fieldName + " must not include a fragment; got: \"" + stripped + "\"");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                    fieldName + " must not include user info; got: \"" + stripped + "\"");
        }
        try {
            if (uri.getHost() != null) {
                // The multi-arg constructor assembles the authority itself, including the
                // brackets around an IPv6 host.
                return new URI(scheme.toLowerCase(Locale.ROOT), null,
                        uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), null, null, null);
            }
            // getHost() is null for underscore hosts (docker-compose service names) and for
            // a non-numeric port. Fall back to the raw authority so http://my_service:8080
            // is accepted.
            String authority = uri.getRawAuthority();
            if (authority == null || authority.isEmpty()) {
                throw new IllegalArgumentException(
                        fieldName + " must include a valid host; got: \"" + stripped + "\"");
            }
            return new URI(
                    scheme.toLowerCase(Locale.ROOT),
                    authority.toLowerCase(Locale.ROOT),
                    null,
                    null,
                    null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    fieldName + " is not a valid URI: " + e.getMessage());
        }
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
            this.configOrigin = parseOrigin(configOrigin, "configOrigin");
            return this;
        }

        /**
         * Override the metrics origin (scheme, host, and optional port; no path).
         * The provider appends {@code /v1/metrics}. For dev/labs environments
         * (e.g. {@code https://metrics.eu.fm.dynatracelabs.com}).
         * Production URL is derived automatically from the SDK key.
         */
        public Builder metricsOrigin(String metricsOrigin) {
            URI uri = parseOrigin(metricsOrigin, "metricsOrigin");
            this.metricsUrl = uri.toString() + "/v1/metrics";
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
