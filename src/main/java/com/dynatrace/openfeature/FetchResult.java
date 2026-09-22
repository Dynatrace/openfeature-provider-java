package com.dynatrace.openfeature;

/**
 * Result of a CDN fetch.
 *
 * <p>{@code body} is null on HTTP 304 (Not Modified); callers should skip updating
 * flagd-core in that case.
 *
 * <p>{@link #skipped()} is a distinct outcome from any HTTP status: no request was made, so it is
 * neither a success nor a failure and must not be reported as either (KB section 2.1.1).
 */
final class FetchResult {

    /** Sentinel status for {@link #skipped()}; never a real HTTP status. */
    private static final int STATUS_SKIPPED = 0;

    private static final FetchResult SKIPPED = new FetchResult(STATUS_SKIPPED, null, null, null);

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

    /** No request was made: a fetch was already in flight, or a Retry-After window is open. */
    static FetchResult skipped() {
        return SKIPPED;
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

    /** {@code Last-Modified} as Unix epoch seconds, to compare against an SSE {@code lastModified}. */
    Long getLastModifiedEpochSeconds() {
        return HttpDates.parseEpochSeconds(lastModified);
    }

    boolean isSkipped() {
        return statusCode == STATUS_SKIPPED;
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
