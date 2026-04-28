package com.dynatrace.openfeature.http;

/**
 * Result of a CDN fetch.
 *
 * <p>{@code body} is null on HTTP 304 (Not Modified); callers should skip updating
 * flagd-core in that case.
 */
public final class FetchResult {

    private final int statusCode;
    private final String body;   // null on 304
    private final String etag;   // null if not present in response

    public FetchResult(int statusCode, String body, String etag) {
        this.statusCode = statusCode;
        this.body = body;
        this.etag = etag;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /** response body, or null for 304 Not Modified */
    public String getBody() {
        return body;
    }

    /** ETag value from response, or null if absent */
    public String getEtag() {
        return etag;
    }

    public boolean isNotModified() {
        return statusCode == 304;
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}
