package com.dynatrace.openfeature;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parsed SSE message envelope per the SSE message format spec.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code type} — required; message discriminator. Unknown types must be ignored by callers.</li>
 *   <li>{@code etag} — optional; quoted ETag of the new upstream resource (e.g. {@code "\"abc123\""}).
 *       Parsed for completeness; server-side providers re-fetch via the CDN URL using standard
 *       conditional GET headers, so this field is not forwarded.</li>
 *   <li>{@code lastModified} — optional; Unix epoch seconds. Used to drop out-of-order messages.</li>
 * </ul>
 *
 * <p>{@code metadata} is intentionally not exposed: per the spec it is type-specific extension
 * data that providers must not forward to OFREP clients.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
final class SseMessage {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Server-side signal to re-fetch the flagd config JSON from the CDN. */
    static final String TYPE_REFETCH_CONFIG = "refetchConfig";

    final String type;
    final String etag;
    final Long lastModified;

    @JsonCreator
    SseMessage(
            @JsonProperty("type") String type,
            @JsonProperty("etag") String etag,
            @JsonProperty("lastModified") Long lastModified) {
        this.type = type;
        this.etag = etag;
        this.lastModified = lastModified;
    }

    /**
     * Parses an SSE message envelope. Returns {@code null} if the payload is not valid JSON
     * or lacks a non-blank {@code type} field — callers should treat null as "unrecognized".
     */
    static SseMessage parse(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            SseMessage msg = MAPPER.readValue(data, SseMessage.class);
            if (msg.type == null || msg.type.isBlank()) {
                return null;
            }
            return msg;
        } catch (Exception e) {
            return null;
        }
    }
}
