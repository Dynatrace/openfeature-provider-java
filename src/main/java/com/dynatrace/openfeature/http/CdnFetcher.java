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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.atomic.AtomicReference;

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

    private final URI url;
    private final HttpClient httpClient;

    private final AtomicReference<String> lastEtag = new AtomicReference<>(null);
    private final AtomicReference<String> lastModifiedHeader = new AtomicReference<>(null);
    private final AtomicReference<ZonedDateTime> lastModifiedAt = new AtomicReference<>(null);

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
    public FetchResult fetch() throws IOException {
        String etag = lastEtag.get();
        String ifModifiedSince = lastModifiedHeader.get();

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(url)
                .GET()
                .timeout(Duration.ofSeconds(10));

        if (etag != null) {
            requestBuilder.header(HEADER_IF_NONE_MATCH, etag);
        }
        if (ifModifiedSince != null) {
            requestBuilder.header(HEADER_IF_MODIFIED_SINCE, ifModifiedSince);
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
            return new FetchResult(304, null, etag, ifModifiedSince);
        }

        String newEtag = response.headers().firstValue(HEADER_ETAG).orElse(null);
        String newLastModified = response.headers().firstValue(HEADER_LAST_MODIFIED).orElse(null);
        String body = response.body();

        if (statusCode >= 200 && statusCode < 300) {
            // stale CDN node defense: reject 200 if Last-Modified isn't newer (CF nodes can serve old).
            // missing Last-Modified: accept (no header = no check).
            ZonedDateTime newLastModifiedAt = parseHttpDate(newLastModified);
            ZonedDateTime stored = lastModifiedAt.get();
            if (newLastModifiedAt != null && stored != null && !newLastModifiedAt.isAfter(stored)) {
                log.warn("CDN returned 200 but Last-Modified ({}) is not later than stored ({}); "
                                + "treating as 304 (suspected stale node)",
                        newLastModified, lastModifiedHeader.get());
                return new FetchResult(304, null, etag, lastModifiedHeader.get());
            }

            if (newEtag != null) {
                lastEtag.set(newEtag);
            }
            if (newLastModified != null) {
                lastModifiedHeader.set(newLastModified);
                if (newLastModifiedAt != null) {
                    lastModifiedAt.set(newLastModifiedAt);
                }
            }
        }

        return new FetchResult(statusCode, body, newEtag, newLastModified);
    }

    /** Parses an HTTP-date (RFC 7231 IMF-fixdate, always GMT). Returns null on parse failure. */
    private static ZonedDateTime parseHttpDate(String value) {
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
}
