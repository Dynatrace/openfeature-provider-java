package com.dynatrace.openfeature;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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

    /**
     * Server-side signal to re-fetch the flagd config JSON from the CDN.
     */
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
     * Parses an SSE payload in either of two shapes:
     * <ul>
     *   <li>Ably envelope — outer JSON object with a {@code data} field that is a JSON-encoded
     *       string containing the actual message.</li>
     *   <li>Flat — the message fields ({@code type}, {@code etag}, {@code lastModified}, etc.) are at the root
     *       as per the OFREP spec.</li>
     * </ul>
     * Returns {@code null} if the payload is not valid JSON or the resolved message lacks a
     * non-blank {@code type} field.
     */
    static SseMessage parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode outer = MAPPER.readTree(payload);
            JsonNode dataNode = outer.get("data");
            String innerJson = (dataNode != null && dataNode.isTextual())
                    ? dataNode.asText()
                    : payload;
            SseMessage msg = MAPPER.readValue(innerJson, SseMessage.class);
            if (msg.type == null || msg.type.isBlank()) {
                return null;
            }
            return msg;
        } catch (Exception e) {
            return null;
        }
    }
}
