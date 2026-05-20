package com.dynatrace.openfeature;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Internal configuration holder for {@link DynatraceProvider}. Not part of the public API.
 *
 * <p>Build via {@link #builder(String)}.
 */
public final class DynatraceProviderOptions {

    /** SDK key format: {token}.server_{geo}_{publicRandom}.{secret}_{sha1} */
    static final Pattern KEY_PATTERN = Pattern.compile(
            "^(dt[0-9]{2})\\.(server)_([a-z]{2,})_([a-zA-Z0-9]{10,})\\.([a-fA-F0-9]{32,})_([a-f0-9]{10,})$");

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
