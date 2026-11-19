package com.dynatrace.openfeature;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Function;

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

    static final String ENV_CONFIG_ORIGIN = "DT_FM_CONFIG_ORIGIN";
    static final String ENV_METRICS_ORIGIN = "DT_FM_METRICS_ORIGIN";
    static final String ENV_SSE_DISABLED = "DT_FM_SSE_DISABLED";

    private final URI configOrigin;
    private final String metricsUrl;
    private final Duration metricsFlushInterval;
    private final int metricsSoftCapDistinctFlags;
    private final int metricsHardCapDistinctFlags;
    // null means not set in code; defers to the env var, then the default
    private final Boolean sseEnabled;

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
        return sseEnabled == null || sseEnabled;
    }

    /**
     * Returns {@code options} with each option not set in code filled from its env var.
     * Env values are validated like code values.
     *
     * @throws IllegalArgumentException if an env value is invalid
     */
    static DynatraceProviderOptions withEnvFallback(DynatraceProviderOptions options, Function<String, String> env) {
        Builder b = new Builder();
        if (options != null) {
            b.configOrigin = options.configOrigin;
            b.metricsUrl = options.metricsUrl;
            b.metricsFlushInterval = options.metricsFlushInterval;
            b.sseEnabled = options.sseEnabled;
        }
        String configOrigin = readEnv(env, ENV_CONFIG_ORIGIN);
        if (b.configOrigin == null && configOrigin != null) {
            b.configOrigin = parseOrigin(configOrigin, ENV_CONFIG_ORIGIN);
        }
        String metricsOrigin = readEnv(env, ENV_METRICS_ORIGIN);
        if (b.metricsUrl == null && metricsOrigin != null) {
            b.metricsUrl = toMetricsUrl(parseOrigin(metricsOrigin, ENV_METRICS_ORIGIN));
        }
        String sseDisabled = readEnv(env, ENV_SSE_DISABLED);
        if (b.sseEnabled == null && sseDisabled != null) {
            b.sseEnabled = !parseBoolean(sseDisabled, ENV_SSE_DISABLED);
        }
        return b.build();
    }

    /** Returns the stripped value of {@code name}, or null if unset or blank. */
    static String readEnv(Function<String, String> env, String name) {
        String value = env.apply(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static boolean parseBoolean(String value, String fieldName) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(
                fieldName + " must be true or false; got: \"" + value + "\"");
    }

    private static String toMetricsUrl(URI origin) {
        return origin.toString() + "/v1/metrics";
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
        Boolean sseEnabled = null;

        /**
         * Override the config origin (scheme, host, and port; no path).
         * For dev/labs environments (e.g. {@code https://cdn.eu.fm.dynatracelabs.com}).
         * Production URL is derived automatically from the SDK key.
         * Falls back to {@code DT_FM_CONFIG_ORIGIN} when not set.
         * If set, carefully consider whether {@link #metricsOrigin} should be set too; an SDK proxy
         * or custom host typically serves both.
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
         * Falls back to {@code DT_FM_METRICS_ORIGIN} when not set.
         */
        public Builder metricsOrigin(String metricsOrigin) {
            this.metricsUrl = toMetricsUrl(parseOrigin(metricsOrigin, "metricsOrigin"));
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

        /**
         * Disables SSE; provider will poll only. Polling interval stays 10s.
         * When not called, {@code DT_FM_SSE_DISABLED} decides.
         */
        public Builder disableSse() {
            this.sseEnabled = false;
            return this;
        }

        public DynatraceProviderOptions build() {
            return new DynatraceProviderOptions(this);
        }
    }
}
