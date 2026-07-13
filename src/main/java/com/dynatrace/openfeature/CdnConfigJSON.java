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
final class CdnConfigJSON {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** {@code eventStreams[].type} value identifying the SSE change-notification stream. */
    static final String STREAM_TYPE_SSE = "sse";

    final List<EventStream> eventStreams;

    @JsonCreator
    CdnConfigJSON(@JsonProperty("eventStreams") List<EventStream> eventStreams) {
        this.eventStreams = eventStreams;
    }

    /**
     * Structured endpoint alternative to the flat {@code url} field.
     * Per ADR-0008: {@code origin} defaults to the provider's configured base URL if absent.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Endpoint {

        final String origin;
        final String requestUri;

        @JsonCreator
        Endpoint(
                @JsonProperty("origin") String origin,
                @JsonProperty("requestUri") String requestUri) {
            this.origin = origin;
            this.requestUri = requestUri;
        }
    }

    /** A single entry in the {@code eventStreams} array. Unknown attributes are ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class EventStream {

        final String type;
        final String url;
        final Endpoint endpoint;

        @JsonCreator
        EventStream(
                @JsonProperty("type") String type,
                @JsonProperty("url") String url,
                @JsonProperty("endpoint") Endpoint endpoint) {
            this.type = type;
            this.url = url;
            this.endpoint = endpoint;
        }
    }

    /**
     * Parses the CDN config body. Returns {@code null} if the payload is not valid JSON — callers
     * should treat null as "no usable config" (the same way {@link SseMessage#parse} behaves).
     */
    static CdnConfigJSON parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, CdnConfigJSON.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the resolved SSE URL for the first {@code sse} event stream entry, or {@code null}
     * if no usable SSE entry exists.
     *
     * <p>Per ADR-0008 two representations are supported, checked in order:
     * <ol>
     *   <li><b>Direct URL</b> — {@code url} field is non-blank → returned as-is.</li>
     *   <li><b>Structured endpoint</b> — {@code endpoint.requestUri} is non-blank → the URL is
     *       constructed as {@code origin + requestUri}, where {@code origin} is taken from
     *       {@code endpoint.origin} when non-blank, or falls back to {@code fallbackOrigin}
     *       (the provider's configured base URL origin).</li>
     * </ol>
     *
     * @param fallbackOrigin scheme + host (+ optional port) to use when {@code endpoint.origin} is
     *                       absent; may be {@code null}, in which case a missing origin yields
     *                       {@code null}.
     */
    String sseUrl(String fallbackOrigin) {
        if (eventStreams == null) {
            return null;
        }
        for (EventStream stream : eventStreams) {
            if (stream != null && STREAM_TYPE_SSE.equals(stream.type)) {
                if (stream.url != null && !stream.url.isBlank()) {
                    return stream.url;
                }
                Endpoint ep = stream.endpoint;
                if (ep != null && ep.requestUri != null && !ep.requestUri.isBlank()) {
                    String origin = (ep.origin != null && !ep.origin.isBlank())
                            ? ep.origin
                            : fallbackOrigin;
                    return (origin != null && !origin.isBlank()) ? origin + ep.requestUri : null;
                }
                return null;
            }
        }
        return null;
    }
}
