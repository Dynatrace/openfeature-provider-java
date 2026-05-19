package com.dynatrace.openfeature;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration options for {@link DynatraceProvider}.
 *
 * <p>Build via {@link #builder(String)}.
 */
public final class DynatraceProviderOptions {

    private static final Logger log = LoggerFactory.getLogger(DynatraceProviderOptions.class);

    /** SDK key format: {token}.server_{geo}_{publicRandom}.{secret}_{sha1} */
    static final Pattern KEY_PATTERN = Pattern.compile(
            "^(dt[0-9]{2})\\.(server)_([a-z]{2,})_([a-zA-Z0-9]{10,})\\.([a-fA-F0-9]{32,})_([a-f0-9]{10,})$");

    /** Default periodic flush interval for the metrics hook. */
    static final Duration DEFAULT_METRICS_FLUSH_INTERVAL = Duration.ofSeconds(30);

    /**
     * Default capacity threshold (distinct flag keys in the live aggregate map) at which the
     * metrics hook triggers an out-of-band flush.
     */
    static final int DEFAULT_METRICS_MAX_AGGREGATE_SIZE = 1_000;

    /**
     * Default hard cap for the aggregate map. Once the live map holds this many distinct flag
     * keys, evaluations of further new flags are dropped (existing flags keep accumulating).
     */
    static final int DEFAULT_METRICS_HARD_CAP_SIZE = 2_000;

    private final String key;
    private final String geo;
    private final String type;
    private final String cdnBaseUrlOverride; // null = derive from key
    private final String metricsBaseUrlOverride; // null = derive from key
    private final Duration metricsFlushInterval;
    private final int metricsMaxAggregateSize;
    private final int metricsHardCapSize;

    private DynatraceProviderOptions(Builder builder) {
        this.key = builder.key;
        this.geo = builder.geo;
        this.type = builder.type;
        this.cdnBaseUrlOverride = builder.cdnBaseUrlOverride;
        this.metricsBaseUrlOverride = builder.metricsBaseUrlOverride;
        this.metricsFlushInterval = builder.metricsFlushInterval;
        this.metricsMaxAggregateSize = DEFAULT_METRICS_MAX_AGGREGATE_SIZE;
        this.metricsHardCapSize = DEFAULT_METRICS_HARD_CAP_SIZE;
    }

    /**
     * The full SDK key including the private segment. Package-private to prevent accidental
     * exposure — use {@link #getPublicKey()} for logging, and {@link #getCdnUrl()} for the
     * CDN endpoint. External callers already hold the key they passed to the builder.
     */
    String getKey() {
        return key;
    }

    /**
     * The public segment of the SDK key ({@code {token}.{platform}_{geo}_{publicRandom}}) —
     * safe for use in logs and error messages. Never exposes the private segment.
     */
    public String getPublicKey() {
        return key.substring(0, key.lastIndexOf('.'));
    }

    /** full CDN URL derived from the key */
    public String getCdnUrl() {
        String base = cdnBaseUrlOverride != null
                ? cdnBaseUrlOverride
                : "https://cdn." + geo + ".fm.dynatrace.com/" + type + "/";
        return base + key + ".json";
    }

    /**
     * Full URL the provider POSTs evaluation-metric batches to.
     */
    public String getMetricsUrl() {
        return metricsBaseUrlOverride != null
                ? metricsBaseUrlOverride
                : "https://metrics." + geo + ".fm.dynatrace.com/";
    }

    /** Periodic flush interval for the evaluation-metrics hook. */
    public Duration getMetricsFlushInterval() {
        return metricsFlushInterval;
    }

    /**
     * Soft capacity threshold (number of distinct flag keys in the live aggregate map) at which
     * the metrics hook triggers an out-of-band flush.
     */
    public int getMetricsMaxAggregateSize() {
        return metricsMaxAggregateSize;
    }

    /**
     * Hard capacity for the aggregate map. Once the live map holds this many distinct flag
     * keys, evaluations of further new flags are dropped (existing flags keep accumulating).
     */
    public int getMetricsHardCapSize() {
        return metricsHardCapSize;
    }

    /**
     * @param key SDK key in the format {@code {token}.server_{geo}_{publicRandom}.{secret}_{sha1}}
     *            (e.g. {@code dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5}).
     *            Use {@link DynatraceProviderOptions#getPublicKey()} to safely surface the key in
     *            logs or errors — never log the full key.
     */
    public static Builder builder(String key) {
        return new Builder(key);
    }

    public static final class Builder {

        private final String key;
        private final String geo;
        private final String type;
        String cdnBaseUrlOverride = null;
        String metricsBaseUrlOverride = null;
        Duration metricsFlushInterval = DEFAULT_METRICS_FLUSH_INTERVAL;

        private Builder(String key) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("key must not be null or blank");
            }
            String trimmed = key.strip();
            Matcher m = KEY_PATTERN.matcher(trimmed);
            if (!m.matches()) {
                // Only expose the public segment (before the last '.') — never the private one.
                int lastDot = trimmed.lastIndexOf('.');
                String publicPart = lastDot > 0 ? trimmed.substring(0, lastDot) : "<redacted>";
                throw new IllegalArgumentException(
                        "invalid key format (public: '" + publicPart + "'); "
                        + "expected dt{NN}.server_{geo}_{rand}.{secret}_{sha}");
            }
            this.key = trimmed;
            this.type = m.group(2);
            this.geo = m.group(3);
        }

        /**
         * Override the CDN base URL.
         */
        public Builder cdnBaseUrl(String cdnBaseUrl) {
            if (cdnBaseUrl == null || cdnBaseUrl.isBlank()) {
                throw new IllegalArgumentException("cdnBaseUrl must not be null or blank");
            }
            this.cdnBaseUrlOverride = cdnBaseUrl.endsWith("/") ? cdnBaseUrl : cdnBaseUrl + "/";
            return this;
        }

        /**
         * Override the metrics endpoint base URL.
         */
        public Builder metricsBaseUrl(String metricsBaseUrl) {
            if (metricsBaseUrl == null || metricsBaseUrl.isBlank()) {
                throw new IllegalArgumentException("metricsBaseUrl must not be null or blank");
            }
            this.metricsBaseUrlOverride = metricsBaseUrl.endsWith("/")
                    ? metricsBaseUrl
                    : metricsBaseUrl + "/";
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

        public DynatraceProviderOptions build() {
            return new DynatraceProviderOptions(this);
        }
    }
}
