package com.dynatrace.openfeature.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Fetches flag configuration JSON from the CDN.
 *
 * <p>Features:
 * <ul>
 *   <li>ETag / If-None-Match revalidation (304 = skip update)</li>
 *   <li>Last-Modified / If-Modified-Since revalidation; defends against stale CDN nodes
 *       by rejecting 200 responses whose {@code Last-Modified} is not strictly later than
 *       the previously stored value.</li>
 * </ul>
 */
public final class CdnFetcher {

    private static final Logger log = LoggerFactory.getLogger(CdnFetcher.class);

    static final String HEADER_ETAG = "ETag";
    static final String HEADER_IF_NONE_MATCH = "If-None-Match";
    static final String HEADER_LAST_MODIFIED = "Last-Modified";
    static final String HEADER_IF_MODIFIED_SINCE = "If-Modified-Since";

    /** RFC 7231 IMF-fixdate canonical form (always GMT, two-digit day). */
    private static final DateTimeFormatter HEADER_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);

    /** must be less than the minimum poll interval (10s) to avoid overlapping requests */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final URI url;
    private final HttpClient httpClient;

    // mutated only in synchronized fetch(); if that changes, use AtomicReference or equivalent
    private CacheState state = CacheState.EMPTY;

    public CdnFetcher(String url, HttpClient httpClient) {
        this.url = URI.create(url);
        this.httpClient = httpClient;
    }

    /**
     * Fetches the flag configuration from the CDN.
     *
     * @return {@link FetchResult}; body is null on 304 or on a stale-node response
     *         (where {@code Last-Modified} is not later than the stored value, treated
     *         as if the CDN had returned 304)
     * @throws IOException on network failure
     */
    public synchronized FetchResult fetch() throws IOException {
        CacheState current = state;
        String currentLastModifiedHeader = toHeaderDate(current.lastModifiedAt);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(url)
                .GET()
                .timeout(REQUEST_TIMEOUT);

        if (current.etag != null) {
            requestBuilder.header(HEADER_IF_NONE_MATCH, current.etag);
        }
        if (currentLastModifiedHeader != null) {
            requestBuilder.header(HEADER_IF_MODIFIED_SINCE, currentLastModifiedHeader);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("request interrupted", e);
        }

        int statusCode = response.statusCode();

        if (statusCode == 304) {
            return new FetchResult(304, null, current.etag, currentLastModifiedHeader);
        }

        String newEtag = response.headers().firstValue(HEADER_ETAG).orElse(null);
        String newLastModified = response.headers().firstValue(HEADER_LAST_MODIFIED).orElse(null);
        String body = response.body();

        if (statusCode >= 200 && statusCode < 300) {
            // stale CDN node defense: reject 200 if Last-Modified isn't newer (CF nodes can serve old).
            // missing Last-Modified: accept (no header = no check).
            ZonedDateTime newLastModifiedAt = fromHeaderDate(newLastModified);
            if (newLastModifiedAt != null
                    && current.lastModifiedAt != null
                    && !newLastModifiedAt.isAfter(current.lastModifiedAt)) {
                log.warn("CDN returned 200 but Last-Modified ({}) is not later than stored ({}); "
                                + "treating as 304 (suspected stale node)",
                        newLastModified, currentLastModifiedHeader);
                return new FetchResult(304, null, current.etag, currentLastModifiedHeader);
            }

            // store only what this response gave us; never mix tokens across requests
            state = new CacheState(newEtag, newLastModifiedAt);
        }

        return new FetchResult(statusCode, body, newEtag, newLastModified);
    }

    
    private static ZonedDateTime fromHeaderDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
        } catch (DateTimeParseException e) {
            log.warn("unparseable HTTP-date '{}': {}", value, e.getMessage());
            return null;
        }
    }

    /** Formats a {@link ZonedDateTime} as canonical IMF-fixdate (RFC 7231). Null-safe. */
    private static String toHeaderDate(ZonedDateTime value) {
        return value == null ? null : HEADER_DATE_FORMATTER.format(value.withZoneSameInstant(ZoneOffset.UTC));
    }

    private static final class CacheState {
        static final CacheState EMPTY = new CacheState(null, null);

        final String etag;
        final ZonedDateTime lastModifiedAt;

        CacheState(String etag, ZonedDateTime lastModifiedAt) {
            this.etag = etag;
            this.lastModifiedAt = lastModifiedAt;
        }
    }
}
