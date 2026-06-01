package com.dynatrace.openfeature;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Typed view of the CDN config JSON body.
 *
 * <p>Only the fields the provider needs are mapped; the bulk of the body is the flagd flag
 * definitions, which are handed to {@code flagd-core} as the raw string rather than parsed here.
 *
 * <p>Like {@link SseMessage}, this is deserialized with {@code FAIL_ON_UNKNOWN_PROPERTIES = false}
 * and {@code @JsonIgnoreProperties(ignoreUnknown = true)} so the server can add new fields (e.g.
 * new {@code eventStreams} entry attributes) without breaking older SDK versions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
final class CdnConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** {@code eventStreams[].type} value identifying the SSE change-notification stream. */
    static final String STREAM_TYPE_SSE = "sse";

    final List<EventStream> eventStreams;

    @JsonCreator
    CdnConfig(@JsonProperty("eventStreams") List<EventStream> eventStreams) {
        this.eventStreams = eventStreams;
    }

    /** A single entry in the {@code eventStreams} array. Unknown attributes are ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class EventStream {

        final String type;
        final String url;

        @JsonCreator
        EventStream(
                @JsonProperty("type") String type,
                @JsonProperty("url") String url) {
            this.type = type;
            this.url = url;
        }
    }

    /**
     * Parses the CDN config body. Returns {@code null} if the payload is not valid JSON — callers
     * should treat null as "no usable config" (the same way {@link SseMessage#parse} behaves).
     */
    static CdnConfig parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, CdnConfig.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the URL of the first {@code sse} event stream, or {@code null} if there is no SSE
     * entry or its URL is absent/blank.
     */
    String sseUrl() {
        if (eventStreams == null) {
            return null;
        }
        for (EventStream stream : eventStreams) {
            if (stream != null && STREAM_TYPE_SSE.equals(stream.type)) {
                String url = stream.url;
                return (url != null && !url.isBlank()) ? url : null;
            }
        }
        return null;
    }
}
