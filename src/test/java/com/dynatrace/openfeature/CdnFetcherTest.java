package com.dynatrace.openfeature;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CdnFetcherTest {

    private static final String MINIMAL_FLAGS_BODY = TestFixtures.MINIMAL_FLAGS;
    private static final String TEST_ETAG = "\"abc123\""; // per RFC 7232 ETags are quoted strings
    private static final String LAST_MODIFIED_OLD = "Mon, 01 Jan 2024 00:00:00 GMT";
    private static final String LAST_MODIFIED_NEW = "Wed, 02 Apr 2025 12:00:00 GMT";

    private MockWebServer server;
    private HttpClient httpClient;

    /** no-sleep retry strategy for fast tests */
    private static final RetryStrategy NO_DELAY_RETRY =
            new RetryStrategy(new Random(42), ms -> {});

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private CdnFetcher fetcher() {
        return new CdnFetcher(server.url("/flags.json").toString(), httpClient, NO_DELAY_RETRY);
    }

    @Test
    void successfulFetchReturnsBodyEtagAndLastModified() throws IOException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(MINIMAL_FLAGS_BODY)
                .addHeader(CdnFetcher.HEADER_ETAG, TEST_ETAG)
                .addHeader(CdnFetcher.HEADER_LAST_MODIFIED, LAST_MODIFIED_OLD));

        FetchResult result = fetcher().fetch();

        assertTrue(result.isSuccess());
        assertEquals(200, result.getStatusCode());
        assertEquals(MINIMAL_FLAGS_BODY, result.getBody());
        assertEquals(TEST_ETAG, result.getEtag());
        assertEquals(LAST_MODIFIED_OLD, result.getLastModified());
    }

    @Test
    void secondFetchSendsConditionalHeaders() throws IOException, InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(MINIMAL_FLAGS_BODY)
                .addHeader(CdnFetcher.HEADER_ETAG, TEST_ETAG)
                .addHeader(CdnFetcher.HEADER_LAST_MODIFIED, LAST_MODIFIED_OLD));
        server.enqueue(new MockResponse().setResponseCode(304));

        CdnFetcher fetcher = fetcher();
        fetcher.fetch();
        fetcher.fetch();

        server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertEquals(TEST_ETAG, second.getHeader(CdnFetcher.HEADER_IF_NONE_MATCH));
        assertEquals(LAST_MODIFIED_OLD, second.getHeader(CdnFetcher.HEADER_IF_MODIFIED_SINCE));
    }

    @Test
    void notModifiedResponseReturnsNullBody() throws IOException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(MINIMAL_FLAGS_BODY)
                .addHeader(CdnFetcher.HEADER_ETAG, TEST_ETAG)
                .addHeader(CdnFetcher.HEADER_LAST_MODIFIED, LAST_MODIFIED_OLD));
        server.enqueue(new MockResponse().setResponseCode(304));

        CdnFetcher fetcher = fetcher();
        fetcher.fetch();
        FetchResult result = fetcher.fetch();

        assertTrue(result.isNotModified());
        assertNull(result.getBody());
    }

    @Test
    void firstFetchDoesNotSendConditionalHeaders() throws IOException, InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        fetcher().fetch();

        RecordedRequest request = server.takeRequest();
        assertNull(request.getHeader(CdnFetcher.HEADER_IF_NONE_MATCH));
        assertNull(request.getHeader(CdnFetcher.HEADER_IF_MODIFIED_SINCE));
    }

    @Test
    void serverErrorIsRetriedAndReturnsLastStatus() throws IOException {
        for (int i = 0; i < RetryStrategy.MAX_ATTEMPTS; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }

        FetchResult result = fetcher().fetch();

        assertEquals(503, result.getStatusCode());
        assertFalse(result.isSuccess());
        assertEquals(RetryStrategy.MAX_ATTEMPTS, server.getRequestCount());
    }

    @Test
    void serverErrorRecoveryOnTheSingleRetry() throws IOException {
        // KB 2.4: two attempts total, so recovery has to happen on the first (and only) retry
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(MINIMAL_FLAGS_BODY)
                .addHeader(CdnFetcher.HEADER_ETAG, TEST_ETAG));

        FetchResult result = fetcher().fetch();

        assertTrue(result.isSuccess());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void ticksInsideTheRetryAfterWindowAreSkippedWithoutNetworkCall() throws IOException {
        // KB 2.3: the 429 ends the fetch and opens a rate-limit window; ticks inside that
        // window make no request, so they are skipped rather than reported as an outcome.
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .addHeader(CdnFetcher.HEADER_RETRY_AFTER, "60"));

        CdnFetcher fetcher = fetcher();
        FetchResult first = fetcher.fetch();
        assertEquals(429, first.getStatusCode(), "the response that opens the window is a real 429");
        assertFalse(first.isSkipped());
        assertTrue(fetcher.isRateLimited());

        FetchResult suppressed = fetcher.fetch();
        assertTrue(suppressed.isSkipped(), "a tick inside the window should be skipped");
        assertFalse(suppressed.isSuccess());
        assertFalse(suppressed.isNotModified(), "a suppressed tick must not masquerade as a 304");
        assertEquals(1, server.getRequestCount(), "no request should be made inside the window");
    }

    @Test
    void fourTwentyNineIsNotRetried() throws IOException {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .addHeader(CdnFetcher.HEADER_RETRY_AFTER, "30"));

        fetcher().fetch();

        assertEquals(1, server.getRequestCount());
    }

    @Test
    void parseRetryAfterSeconds() {
        assertEquals(30_000L, CdnFetcher.parseRetryAfterMs("30"));
        assertEquals(120_000L, CdnFetcher.parseRetryAfterMs("120"));
    }

    @Test
    void parseRetryAfterMissingDefaultsTo60s() {
        assertEquals(60_000L, CdnFetcher.parseRetryAfterMs(null));
        assertEquals(60_000L, CdnFetcher.parseRetryAfterMs(""));
        assertEquals(60_000L, CdnFetcher.parseRetryAfterMs("   "));
    }

    @Test
    void parseRetryAfterInvalidDefaultsTo60s() {
        assertEquals(60_000L, CdnFetcher.parseRetryAfterMs("not-a-number-or-date"));
    }

    @Test
    void staleNodeWithOlderLastModifiedIsTreatedAsNotModified() throws IOException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(MINIMAL_FLAGS_BODY)
                .addHeader(CdnFetcher.HEADER_ETAG, TEST_ETAG)
                .addHeader(CdnFetcher.HEADER_LAST_MODIFIED, LAST_MODIFIED_NEW));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("stale-body")
                .addHeader(CdnFetcher.HEADER_ETAG, "\"stale-etag\"")
                .addHeader(CdnFetcher.HEADER_LAST_MODIFIED, LAST_MODIFIED_OLD));

        CdnFetcher fetcher = fetcher();
        fetcher.fetch();
        FetchResult result = fetcher.fetch();

        assertTrue(result.isNotModified());
        assertNull(result.getBody());
    }

    @Test
    void equalLastModifiedIsTreatedAsNotModified() throws IOException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(MINIMAL_FLAGS_BODY)
                .addHeader(CdnFetcher.HEADER_ETAG, TEST_ETAG)
                .addHeader(CdnFetcher.HEADER_LAST_MODIFIED, LAST_MODIFIED_OLD));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("possibly-stale-body")
                .addHeader(CdnFetcher.HEADER_ETAG, TEST_ETAG)
                .addHeader(CdnFetcher.HEADER_LAST_MODIFIED, LAST_MODIFIED_OLD));

        CdnFetcher fetcher = fetcher();
        fetcher.fetch();
        FetchResult result = fetcher.fetch();

        assertTrue(result.isNotModified());
        assertNull(result.getBody());
    }

    @Test
    void responseMissingLastModifiedIsAccepted() throws IOException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(MINIMAL_FLAGS_BODY)
                .addHeader(CdnFetcher.HEADER_ETAG, TEST_ETAG)
                .addHeader(CdnFetcher.HEADER_LAST_MODIFIED, LAST_MODIFIED_OLD));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("new-body")
                .addHeader(CdnFetcher.HEADER_ETAG, "\"new-etag\""));

        CdnFetcher fetcher = fetcher();
        fetcher.fetch();
        FetchResult result = fetcher.fetch();

        assertTrue(result.isSuccess());
        assertEquals("new-body", result.getBody());
    }

    @Test
    void concurrentFetchIsSkippedNotReportedAs304() throws Exception {
        // KB 2.1.1: a contended fetch must be distinguishable from a successful revalidation.
        // Reporting it as 304 makes it look like a successful poll and resets the grace timer.
        CountDownLatch firstInFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                firstInFlight.countDown();
                release.await(5, TimeUnit.SECONDS);
                return new MockResponse().setResponseCode(200).setBody(MINIMAL_FLAGS_BODY);
            }
        });

        CdnFetcher fetcher = fetcher();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<FetchResult> first = executor.submit(fetcher::fetch);
            assertTrue(firstInFlight.await(2, TimeUnit.SECONDS), "first fetch should reach the server");

            FetchResult contended = fetcher.fetch();

            assertTrue(contended.isSkipped(), "a contended fetch should report as skipped");
            assertFalse(contended.isNotModified(), "a skipped fetch must not masquerade as a 304");
            assertFalse(contended.isSuccess());
            assertFalse(contended.isRateLimited());

            release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).isSuccess(),
                    "the in-flight fetch should complete normally");
            assertEquals(1, server.getRequestCount(), "the contended caller must not make a request");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
