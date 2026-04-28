package com.dynatrace.openfeature.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches flag configuration JSON from the CDN.
 *
 * <p>Features:
 * <ul>
 *   <li>ETag / If-None-Match revalidation (304 = skip update)</li>
 * </ul>
 */
public final class CdnFetcher {

    static final String HEADER_ETAG = "ETag";
    static final String HEADER_IF_NONE_MATCH = "If-None-Match";

    private final URI url;
    private final HttpClient httpClient;

    private final AtomicReference<String> lastEtag = new AtomicReference<>(null);

    public CdnFetcher(String url, HttpClient httpClient) {
        this.url = URI.create(url);
        this.httpClient = httpClient;
    }

    /**
     * Fetches the flag configuration from the CDN.
     *
     * @return {@link FetchResult}; body is null on 304
     * @throws IOException on network failure
     */
    public FetchResult fetch() throws IOException {
        String etag = lastEtag.get();

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(url)
                .GET()
                .timeout(Duration.ofSeconds(10));

        if (etag != null) {
            requestBuilder.header(HEADER_IF_NONE_MATCH, etag);
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
            return new FetchResult(304, null, etag);
        }

        String newEtag = response.headers().firstValue(HEADER_ETAG).orElse(null);
        String body = response.body();

        if (statusCode >= 200 && statusCode < 300 && newEtag != null) {
            lastEtag.set(newEtag);
        }

        return new FetchResult(statusCode, body, newEtag);
    }
}
