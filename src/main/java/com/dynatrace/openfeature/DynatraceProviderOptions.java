package com.dynatrace.openfeature;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration options for {@link DynatraceProvider}.
 *
 * <p>Build via {@link #builder(String)}.
 */
public final class DynatraceProviderOptions {

    /** SDK key format: dt_server_{geo}_{32-char id}; geo is alphabetic. */
    static final Pattern KEY_PATTERN = Pattern.compile("^dt_(server)_([a-zA-Z]+)_([a-zA-Z0-9]{32})$");

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
     * The SDK key; format {@code dt_server_{geo}_{id}} (e.g. {@code dt_server_us_de848e97...}).
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
     * @param key SDK key in the format {@code dt_server_{geo}_{id}}
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
            String trimmed = key.strip();
            Matcher m = KEY_PATTERN.matcher(trimmed);
            if (!m.matches()) {
                throw new IllegalArgumentException(
                        "invalid key '" + trimmed + "'; expected dt_server_{geo}_{32-char id}");
            }
            this.key = trimmed;
            this.type = m.group(1);
            this.geo = m.group(2);
        }

        /**
         * Override the CDN base URL. A trailing {@code /} is auto-appended if missing.
         * For dev/labs environments (e.g. {@code https://cdn.us.fm.dynatracelabs.com/server/}).
         * Production URL is derived automatically from the key.
         */
        public Builder cdnBaseUrl(String cdnBaseUrl) {
            if (cdnBaseUrl == null || cdnBaseUrl.isBlank()) {
                throw new IllegalArgumentException("cdnBaseUrl must not be null or blank");
            }
            this.cdnBaseUrlOverride = cdnBaseUrl.endsWith("/") ? cdnBaseUrl : cdnBaseUrl + "/";
            return this;
        }

        public DynatraceProviderOptions build() {
            return new DynatraceProviderOptions(this);
        }
    }
}
