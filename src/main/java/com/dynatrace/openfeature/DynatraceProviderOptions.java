package com.dynatrace.openfeature;

/**
 * Optional configuration for {@link DynatraceProvider}.
 *
 * <p>Build via {@link #builder()}.
 */
public final class DynatraceProviderOptions {

    private final String cdnBaseUrl;

    private DynatraceProviderOptions(Builder builder) {
        this.cdnBaseUrl = builder.cdnBaseUrl;
    }

    String getCdnBaseUrl() {
        return cdnBaseUrl;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        String cdnBaseUrl = null;

        /**
         * Override the config origin (e.g. a local proxy or lower environment).
         * A trailing {@code /} is auto-appended if missing.
         * Production origin is derived automatically from the SDK key.
         */
        public Builder cdnBaseUrl(String cdnBaseUrl) {
            if (cdnBaseUrl == null || cdnBaseUrl.isBlank()) {
                throw new IllegalArgumentException("cdnBaseUrl must not be null or blank");
            }
            this.cdnBaseUrl = cdnBaseUrl.endsWith("/") ? cdnBaseUrl : cdnBaseUrl + "/";
            return this;
        }

        public DynatraceProviderOptions build() {
            return new DynatraceProviderOptions(this);
        }
    }
}
