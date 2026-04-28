package com.dynatrace.openfeature;

/**
 * Configuration options for {@link DynatraceProvider}.
 *
 * <p>Build via {@link #builder(String)}.
 */
public final class DynatraceProviderOptions {

    static final String DEFAULT_CDN_BASE_URL = "https://cdn.us.fm.dynatrace.com/server/";

    private final String key;
    private final String cdnBaseUrl;

    private DynatraceProviderOptions(Builder builder) {
        this.key = builder.key;
        this.cdnBaseUrl = builder.cdnBaseUrl;
    }

    /** high-entropy key identifying the flag configuration (e.g. {@code de848e97a9cc4cc78aae568e65f49a9d}) */
    public String getKey() {
        return key;
    }

    /** CDN base URL including trailing slash; defaults to {@value DEFAULT_CDN_BASE_URL} */
    public String getCdnBaseUrl() {
        return cdnBaseUrl;
    }

    /** full CDN URL assembled from base URL and key */
    public String getCdnUrl() {
        return cdnBaseUrl + "server_dt_server_" + key + ".json";
    }

    /**
     * @param key high-entropy key identifying the flag configuration
     */
    public static Builder builder(String key) {
        return new Builder(key);
    }

    public static final class Builder {

        private final String key;
        private String cdnBaseUrl = DEFAULT_CDN_BASE_URL;

        private Builder(String key) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("key must not be null or blank");
            }
            this.key = key;
        }

        /**
         * Override the CDN base URL (include trailing slash).
         * Useful for dev/labs environments (e.g. {@code https://cdn.us.fm.dynatracelabs.com/server/}).
         */
        public Builder cdnBaseUrl(String cdnBaseUrl) {
            if (cdnBaseUrl == null || cdnBaseUrl.isBlank()) {
                throw new IllegalArgumentException("cdnBaseUrl must not be null or blank");
            }
            this.cdnBaseUrl = cdnBaseUrl;
            return this;
        }

        public DynatraceProviderOptions build() {
            return new DynatraceProviderOptions(this);
        }
    }
}
