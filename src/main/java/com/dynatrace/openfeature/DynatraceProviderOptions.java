package com.dynatrace.openfeature;

import java.util.Set;

/**
 * Configuration options for {@link DynatraceProvider}.
 *
 * <p>Build via {@link #builder(String)}.
 */
public final class DynatraceProviderOptions {

    static final Set<String> ALLOWED_TYPES = Set.of("server", "web", "mobile");
    static final Set<String> ALLOWED_GEOS = Set.of("us", "eu", "oc", "apac");
    static final int ID_LENGTH = 32;

    private final String key;
    private final String geo;
    private final String type;
    private final String cdnBaseUrlOverride; // null = derive from key

    private DynatraceProviderOptions(Builder builder) {
        this.key = builder.key;
        this.geo = builder.geo;
        this.type = builder.type;
        this.cdnBaseUrlOverride = builder.cdnBaseUrlOverride;
    }

    /**
     * The SDK key; format {@code dt_{type}_{geo}_{id}} (e.g. {@code dt_server_us_de848e97...}).
     */
    public String getKey() {
        return key;
    }

    /** full CDN URL derived from the key */
    public String getCdnUrl() {
        String base = cdnBaseUrlOverride != null
                ? cdnBaseUrlOverride
                : "https://cdn." + geo + ".fm.dynatrace.com/" + type + "/";
        return base + key + ".json";
    }

    /**
     * @param key SDK key in the format {@code dt_{type}_{geo}_{id}}
     *            (e.g. {@code dt_server_us_de848e97a9cc4cc78aae568e65f49a9d})
     */
    public static Builder builder(String key) {
        return new Builder(key);
    }

    public static final class Builder {

        private final String key;
        private final String geo;
        private final String type;
        String cdnBaseUrlOverride = null; // package-private; for dev/testing only

        private Builder(String key) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("key must not be null or blank");
            }
            // format: dt_{type}_{geo}_{id}
            String[] parts = key.split("_", 4);
            if (parts.length != 4 || !"dt".equals(parts[0])) {
                throw new IllegalArgumentException(
                        "invalid key format; expected dt_{type}_{geo}_{id}, got: " + key);
            }
            String parsedType = parts[1];
            String parsedGeo = parts[2];
            String parsedId = parts[3];

            if (!ALLOWED_TYPES.contains(parsedType)) {
                throw new IllegalArgumentException(
                        "invalid key type '" + parsedType + "'; expected one of " + ALLOWED_TYPES);
            }
            if (!ALLOWED_GEOS.contains(parsedGeo)) {
                throw new IllegalArgumentException(
                        "invalid key geo '" + parsedGeo + "'; expected one of " + ALLOWED_GEOS);
            }
            if (parsedId.length() != ID_LENGTH) {
                throw new IllegalArgumentException(
                        "invalid key id '" + parsedId + "'; expected " + ID_LENGTH + " characters");
            }

            this.key = key;
            this.type = parsedType;
            this.geo = parsedGeo;
        }

        /**
         * Override the CDN base URL (must end with {@code /}).
         * For dev/labs environments (e.g. {@code https://cdn.us.fm.dynatracelabs.com/server/}).
         * Production URL is derived automatically from the key.
         */
        public Builder cdnBaseUrl(String cdnBaseUrl) {
            if (cdnBaseUrl == null || cdnBaseUrl.isBlank()) {
                throw new IllegalArgumentException("cdnBaseUrl must not be null or blank");
            }
            if (!cdnBaseUrl.endsWith("/")) {
                throw new IllegalArgumentException(
                        "cdnBaseUrl must end with '/' (got: " + cdnBaseUrl + ")");
            }
            this.cdnBaseUrlOverride = cdnBaseUrl;
            return this;
        }

        public DynatraceProviderOptions build() {
            return new DynatraceProviderOptions(this);
        }
    }
}
