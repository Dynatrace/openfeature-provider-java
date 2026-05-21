package com.dynatrace.openfeature;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration options for {@link DynatraceProvider}.
 *
 * <p>Build via {@link #builder()} and pass as the second argument to
 * {@link DynatraceProvider#DynatraceProvider(String, DynatraceProviderOptions)}.
 */
public final class DynatraceProviderOptions {

    /** SDK key format: {token}.server_{geo}_{publicRandom}.{secret}_{sha1} */
    static final Pattern KEY_PATTERN = Pattern.compile(
            "^(dt[0-9]{2})\\.(server)_([a-z]{2,})_([a-zA-Z0-9]{10,})\\.([a-fA-F0-9]{32,})_([a-f0-9]{10,})$");

    private final String key;
    private final String geo;
    private final String type;
    private final String cdnBaseUrlOverride; // null = derive from key

    private DynatraceProviderOptions(String key, String geo, String type, String cdnBaseUrlOverride) {
        this.key = key;
        this.geo = geo;
        this.type = type;
        this.cdnBaseUrlOverride = cdnBaseUrlOverride;
    }

    // Used by builder() — key/geo/type filled in later by parse()
    private DynatraceProviderOptions(Builder builder) {
        this.key = null;
        this.geo = null;
        this.type = null;
        this.cdnBaseUrlOverride = builder.cdnBaseUrlOverride;
    }

    /**
     * Validates {@code sdkKey}, merges with {@code opts}, and returns a fully resolved options object.
     * Called by {@link DynatraceProvider} constructors.
     */
    static DynatraceProviderOptions parse(String sdkKey, DynatraceProviderOptions opts) {
        if (sdkKey == null || sdkKey.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }
        String trimmed = sdkKey.strip();
        Matcher m = KEY_PATTERN.matcher(trimmed);
        if (!m.matches()) {
            int lastDot = trimmed.lastIndexOf('.');
            String publicPart = lastDot > 0 ? trimmed.substring(0, lastDot) : "<redacted>";
            throw new IllegalArgumentException(
                    "invalid key format (public: '" + publicPart + "'); "
                    + "expected dt{NN}.server_{geo}_{rand}.{secret}_{sha}");
        }
        String type = m.group(2);
        String geo = m.group(3);
        String override = opts != null ? opts.cdnBaseUrlOverride : null;
        return new DynatraceProviderOptions(trimmed, geo, type, override);
    }

    /**
     * The full SDK key. Package-private — use {@link #getPublicKey()} for logging.
     */
    String getKey() {
        return key;
    }

    /**
     * The public segment of the SDK key ({@code {token}.{platform}_{geo}_{publicRandom}}) —
     * safe for use in logs and error messages.
     */
    public String getPublicKey() {
        return key.substring(0, key.lastIndexOf('.'));
    }

    /** Full CDN URL derived from the key, with any base URL override applied. */
    public String getCdnUrl() {
        String base = cdnBaseUrlOverride != null
                ? cdnBaseUrlOverride
                : "https://cdn." + geo + ".fm.dynatrace.com/" + type + "/";
        return base + key + ".json";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        String cdnBaseUrlOverride = null;

        /**
         * Override the config origin (e.g. a local proxy or lower environment).
         * A trailing {@code /} is auto-appended if missing.
         * Production origin is derived automatically from the key.
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
