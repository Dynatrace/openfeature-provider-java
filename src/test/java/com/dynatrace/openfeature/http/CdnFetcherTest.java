package com.dynatrace.openfeature.http;

import com.dynatrace.openfeature.TestFixtures;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.*;

class CdnFetcherTest {

    private static final String MINIMAL_FLAGS_BODY = TestFixtures.MINIMAL_FLAGS;
    private static final String TEST_ETAG = "\"abc123\""; // per RFC 7232 ETags are quoted strings
    private static final String LAST_MODIFIED_OLD = "Mon, 01 Jan 2024 00:00:00 GMT";
    private static final String LAST_MODIFIED_NEW = "Wed, 02 Apr 2025 12:00:00 GMT";

    private MockWebServer server;
    private HttpClient httpClient;

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
        return new CdnFetcher(server.url("/flags.json").toString(), httpClient);
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
    void serverErrorReturnsStatus() throws IOException {
        server.enqueue(new MockResponse().setResponseCode(503));

        FetchResult result = fetcher().fetch();

        assertEquals(503, result.getStatusCode());
        assertFalse(result.isSuccess());
    }

    @Test
    void staleNodeWithOlderLastModifiedIsTreatedAsNotModified() throws IOException {
        // first fetch: establishes baseline with the newer Last-Modified
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(MINIMAL_FLAGS_BODY)
                .addHeader(CdnFetcher.HEADER_ETAG, TEST_ETAG)
                .addHeader(CdnFetcher.HEADER_LAST_MODIFIED, LAST_MODIFIED_NEW));
        // second fetch: a stale CDN node returns 200 with an older Last-Modified
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("stale-body")
                .addHeader(CdnFetcher.HEADER_ETAG, "\"stale-etag\"")
                .addHeader(CdnFetcher.HEADER_LAST_MODIFIED, LAST_MODIFIED_OLD));

        CdnFetcher fetcher = fetcher();
        fetcher.fetch();
        FetchResult result = fetcher.fetch();

        // we should reject the stale response and surface it as not-modified
        assertTrue(result.isNotModified());
        assertNull(result.getBody());
    }

    @Test
    void equalLastModifiedIsTreatedAsNotModified() throws IOException {
        // first fetch establishes baseline
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(MINIMAL_FLAGS_BODY)
                .addHeader(CdnFetcher.HEADER_ETAG, TEST_ETAG)
                .addHeader(CdnFetcher.HEADER_LAST_MODIFIED, LAST_MODIFIED_OLD));
        // second fetch: CDN node returns 200 with the same Last-Modified (cached copy)
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("possibly-stale-body")
                .addHeader(CdnFetcher.HEADER_ETAG, TEST_ETAG)
                .addHeader(CdnFetcher.HEADER_LAST_MODIFIED, LAST_MODIFIED_OLD));

        CdnFetcher fetcher = fetcher();
        fetcher.fetch();
        FetchResult result = fetcher.fetch();

        // !isAfter() rejects equality; equal Last-Modified treated as 304
        assertTrue(result.isNotModified());
        assertNull(result.getBody());
    }

    @Test
    void responseMissingLastModifiedIsAccepted() throws IOException {
        // first fetch establishes Last-Modified
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(MINIMAL_FLAGS_BODY)
                .addHeader(CdnFetcher.HEADER_ETAG, TEST_ETAG)
                .addHeader(CdnFetcher.HEADER_LAST_MODIFIED, LAST_MODIFIED_OLD));
        // second fetch returns 200 with no Last-Modified at all
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("new-body")
                .addHeader(CdnFetcher.HEADER_ETAG, "\"new-etag\""));

        CdnFetcher fetcher = fetcher();
        fetcher.fetch();
        FetchResult result = fetcher.fetch();

        // we accept the response when Last-Modified is absent (can't apply staleness check)
        assertTrue(result.isSuccess());
        assertEquals("new-body", result.getBody());
    }
}
