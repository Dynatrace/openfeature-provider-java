package com.dynatrace.openfeature;

/**
 * Result of a CDN fetch.
 *
 * <p>{@code body} is null on HTTP 304 (Not Modified); callers should skip updating
 * flagd-core in that case.
 */
final class FetchResult {

    private final int statusCode;
    private final String body;          // null on 304
    private final String etag;          // null if not present in response
    private final String lastModified;  // null if not present in response

    FetchResult(int statusCode, String body, String etag, String lastModified) {
        this.statusCode = statusCode;
        this.body = body;
        this.etag = etag;
        this.lastModified = lastModified;
    }

    int getStatusCode() {
        return statusCode;
    }

    /** response body, or null for 304 Not Modified */
    String getBody() {
        return body;
    }

    /** ETag value from response, or null if absent */
    String getEtag() {
        return etag;
    }

    /** Last-Modified value (RFC 1123) from response, or null if absent */
    String getLastModified() {
        return lastModified;
    }

    boolean isNotModified() {
        return statusCode == 304;
    }

    boolean isRateLimited() {
        return statusCode == 429;
    }

    boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}
