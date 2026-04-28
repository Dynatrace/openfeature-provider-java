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
    private static final String TEST_ETAG = "\"abc123\""; // per RFC 7232 Etags are quoted strings

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
    void successfulFetchReturnsBodyAndEtag() throws IOException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(MINIMAL_FLAGS_BODY)
                .addHeader(CdnFetcher.HEADER_ETAG, TEST_ETAG));

        FetchResult result = fetcher().fetch();

        assertTrue(result.isSuccess());
        assertEquals(200, result.getStatusCode());
        assertEquals(MINIMAL_FLAGS_BODY, result.getBody());
        assertEquals(TEST_ETAG, result.getEtag());
    }

    @Test
    void secondFetchSendsIfNoneMatchHeader() throws IOException, InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(MINIMAL_FLAGS_BODY)
                .addHeader(CdnFetcher.HEADER_ETAG, TEST_ETAG));
        server.enqueue(new MockResponse().setResponseCode(304));

        CdnFetcher fetcher = fetcher();
        fetcher.fetch();
        fetcher.fetch();

        server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertEquals(TEST_ETAG, second.getHeader(CdnFetcher.HEADER_IF_NONE_MATCH));
    }

    @Test
    void notModifiedResponseReturnsNullBody() throws IOException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(MINIMAL_FLAGS_BODY)
                .addHeader(CdnFetcher.HEADER_ETAG, TEST_ETAG));
        server.enqueue(new MockResponse().setResponseCode(304));

        CdnFetcher fetcher = fetcher();
        fetcher.fetch();
        FetchResult result = fetcher.fetch();

        assertTrue(result.isNotModified());
        assertNull(result.getBody());
    }

    @Test
    void firstFetchDoesNotSendIfNoneMatch() throws IOException, InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        fetcher().fetch();

        RecordedRequest request = server.takeRequest();
        assertNull(request.getHeader(CdnFetcher.HEADER_IF_NONE_MATCH));
    }

    @Test
    void serverErrorReturnsStatus() throws IOException {
        server.enqueue(new MockResponse().setResponseCode(503));

        FetchResult result = fetcher().fetch();

        assertEquals(503, result.getStatusCode());
        assertFalse(result.isSuccess());
    }
}
