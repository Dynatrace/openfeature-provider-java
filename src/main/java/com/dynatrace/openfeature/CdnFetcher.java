package com.dynatrace.openfeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fetches flag configuration JSON from the CDN.
 *
 * <p>Features:
 * <ul>
 *   <li>ETag / If-None-Match revalidation (304 = skip update)</li>
 *   <li>Last-Modified / If-Modified-Since revalidation; defends against stale CDN nodes
 *       by rejecting 200 responses whose {@code Last-Modified} is not strictly later than
 *       the previously stored value.</li>
 *   <li>Bounded retry on 5xx / network errors (via {@link RetryStrategy})</li>
 *   <li>429 / Retry-After rate limiting; a 429 ends the fetch and is never retried in-place</li>
 *   <li>Single-flight: a fetch arriving while another is in progress is skipped, not queued</li>
 * </ul>
 */
final class CdnFetcher {

    private static final Logger log = LoggerFactory.getLogger(CdnFetcher.class);

    static final String HEADER_ETAG = "ETag";
    static final String HEADER_IF_NONE_MATCH = "If-None-Match";
    static final String HEADER_LAST_MODIFIED = "Last-Modified";
    static final String HEADER_IF_MODIFIED_SINCE = "If-Modified-Since";
    static final String HEADER_RETRY_AFTER = "Retry-After";

    /**
     * Bounds a single attempt. With {@link RetryStrategy#MAX_ATTEMPTS} this keeps a whole fetch
     * shorter than the shortest poll interval (KB section 2.1); changing it means re-checking
     * that invariant, which {@code RetryBoundInvariantTest} guards.
     */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

    /** default Retry-After duration when 429 has no header */
    private static final long DEFAULT_RETRY_AFTER_MS = 60_000L;

    private final URI url;
    private final HttpClient httpClient;
    private final RetryStrategy retryStrategy;

    // mutated in doFetch() under fetchLock; also cleared by invalidateCache() from other threads
    private volatile CacheState state = CacheState.EMPTY;

    // rate limit tracking; read outside the lock by isRateLimited()
    private final AtomicLong rateLimitedUntil = new AtomicLong(0);

    private final ReentrantLock fetchLock = new ReentrantLock();

    CdnFetcher(String url, HttpClient httpClient) {
        this(url, httpClient, new RetryStrategy());
    }

    // visible for testing
    CdnFetcher(String url, HttpClient httpClient, RetryStrategy retryStrategy) {
        this.url = URI.create(url);
        this.httpClient = httpClient;
        this.retryStrategy = retryStrategy;
    }

    /**
     * Drops the cached ETag / Last-Modified so the next fetch is unconditional.
     *
     * <p>Call this when a fetched body could not be applied. The validators are stored as soon as
     * a 2xx arrives, before the caller has had a chance to reject the body, so leaving them in
     * place would make the next fetch revalidate to 304 ("what you have is current") for a config
     * that was never actually loaded.
     */
    void invalidateCache() {
        state = CacheState.EMPTY;
    }

    /** Returns true if a 429 Retry-After period is still active. */
    boolean isRateLimited() {
        return System.currentTimeMillis() < rateLimitedUntil.get();
    }

    /**
     * Fetches the flag configuration from the CDN with retry.
     *
     * <p>Returns {@link FetchResult#skipped()} without making a request when a fetch is already
     * in flight (KB section 2.1.1) or when inside an active Retry-After window (KB section 2.3).
     *
     * <p>{@link PollingManager} already serialises its own fetches, so the in-flight guard is
     * defence in depth for callers outside it: {@link DynatraceProvider#initialize} fetches on the
     * caller's thread. It is what keeps {@link #state} consistent, since a conditional request
     * reads the validators before sending and stores the new pair after, and interleaving two
     * fetches could store an ETag and a Last-Modified that came from different responses.
     *
     * @return {@link FetchResult}; body is null on 304, 429, a skip, or on a stale-node response
     * @throws IOException on network failure after all retry attempts exhausted
     */
    FetchResult fetch() throws IOException {
        if (isRateLimited()) {
            log.debug("inside Retry-After window; skipping fetch (no request made)");
            return FetchResult.skipped();
        }
        if (!fetchLock.tryLock()) {
            log.debug("fetch already in progress; skipping (no request made)");
            return FetchResult.skipped();
        }
        try {
            return retryStrategy.execute(this::doFetch);
        } finally {
            fetchLock.unlock();
        }
    }

    private FetchResult doFetch() throws IOException {
        CacheState current = state;
        String currentLastModifiedHeader = HttpDates.format(current.lastModifiedAt);

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

        if (statusCode == 429) {
            String retryAfter = response.headers().firstValue(HEADER_RETRY_AFTER).orElse(null);
            long retryAfterMs = parseRetryAfterMs(retryAfter);
            rateLimitedUntil.set(System.currentTimeMillis() + retryAfterMs);
            log.warn("rate limited (429); pausing fetches for {}ms", retryAfterMs);
            return new FetchResult(429, null, null, null);
        }

        String newEtag = response.headers().firstValue(HEADER_ETAG).orElse(null);
        String newLastModified = response.headers().firstValue(HEADER_LAST_MODIFIED).orElse(null);
        String body = response.body();

        if (statusCode >= 200 && statusCode < 300) {
            // stale CDN node defense: reject 200 if Last-Modified isn't newer (CF nodes can serve old).
            // missing Last-Modified: accept (no header = no check).
            ZonedDateTime newLastModifiedAt = HttpDates.parse(newLastModified);
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

    /**
     * Parses the Retry-After header value as either seconds or an HTTP-date.
     * Returns milliseconds to wait. Falls back to {@link #DEFAULT_RETRY_AFTER_MS}.
     */
    static long parseRetryAfterMs(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) {
            return DEFAULT_RETRY_AFTER_MS;
        }
        // try as seconds
        try {
            return Long.parseLong(retryAfter.trim()) * 1000L;
        } catch (NumberFormatException ignored) {
        }
        // try as HTTP-date
        try {
            Instant retryAt = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(retryAfter.trim()));
            return Math.max(retryAt.toEpochMilli() - System.currentTimeMillis(), 0L);
        } catch (DateTimeParseException ignored) {
        }
        return DEFAULT_RETRY_AFTER_MS;
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
