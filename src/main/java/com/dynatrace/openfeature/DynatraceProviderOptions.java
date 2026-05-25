package com.dynatrace.openfeature;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Optional configuration for {@link DynatraceProvider}.
 *
 * <p>Build via {@link #builder()}.
 */
public final class DynatraceProviderOptions {

    private final String configOrigin;

    private DynatraceProviderOptions(Builder builder) {
        this.configOrigin = builder.configOrigin;
    }

    String getConfigOrigin() {
        return configOrigin;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        String configOrigin = null;

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
            try {
                URI uri = new URI(stripped);
                String path = uri.getPath();
                if (path != null && !path.isEmpty() && !path.equals("/")) {
                    throw new IllegalArgumentException(
                            "configOrigin must not include a path; got: \"" + stripped + "\"");
                }
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("configOrigin is not a valid URI: " + e.getMessage());
            }
            this.configOrigin = stripped;
            return this;
        }

        public DynatraceProviderOptions build() {
            return new DynatraceProviderOptions(this);
        }
    }
}
