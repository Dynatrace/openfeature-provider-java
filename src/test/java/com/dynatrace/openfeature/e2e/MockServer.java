package com.dynatrace.openfeature.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the containerized mock server that emulates every component the provider talks to
 * (CDN config endpoint, SSE and metrics ingest), plus an HTTP control plane to script responses
 * and inspect received requests.
 */
final class MockServer {

    /** Port the mock server listens on inside the container. */
    private static final int CONTAINER_PORT = 8080;

    /** Mock server image repository; the tag comes from the pinned harness's version.txt. */
    private static final String IMAGE_REPO = "ghcr.io/dynatrace/fm-provider-mock-server";

    /** Classpath location of the harness's version.txt (see the testResource in pom.xml). */
    private static final String VERSION_RESOURCE = "/testharness/version.txt";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile GenericContainer<?> container;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;

    private MockServer(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Lazily starts the singleton container (if needed) and returns a client bound to it. */
    static synchronized MockServer get() {
        if (container == null || !container.isRunning()) {
            container = startContainer();
        }
        String base = "http://" + container.getHost() + ":" + container.getMappedPort(CONTAINER_PORT);
        return new MockServer(base);
    }

    /** True if the container has already been started (used by hooks to reset only when relevant). */
    static boolean isRunning() {
        return container != null && container.isRunning();
    }

    /**
     * Resolves the image from the pinned submodule's version.txt, so the mock server can never drift
     * from the spec commit the harness is pinned to. Override with -Dmockserver.image=... or
     * MOCKSERVER_IMAGE=... (e.g. to run a locally built image).
     */
    private static String defaultImage() {
        try (InputStream in = MockServer.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "missing " + VERSION_RESOURCE + " on the test classpath; run `git submodule"
                                + " update --init fm-provider-test-harness`");
            }
            return IMAGE_REPO + ":v" + new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + VERSION_RESOURCE, e);
        }
    }

    private static GenericContainer<?> startContainer() {
        String image = System.getProperty("mockserver.image");
        if (image == null) {
            image = System.getenv("MOCKSERVER_IMAGE");
        }
        if (image == null) {
            image = defaultImage();
        }
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(CONTAINER_PORT)
                .waitingFor(Wait.forHttp("/__control__/health").forStatusCode(200))
                .withStartupTimeout(Duration.ofSeconds(60));
        c.start();
        return c;
    }

    /** Origin (scheme://host:port, no path) the provider is pointed at via {@code configOrigin} and {@code metricsOrigin}. */
    String origin() {
        return baseUrl;
    }

    /** Clears programmed CDN responses, recorded requests and rate-limit state. */
    void reset() {
        send("POST", "/__control__/reset", null);
    }

    /**
     * Programs the ordered CDN response sequence served at {@code GET /server/*}. Each fetch consumes
     * the next entry; once exhausted the last entry is served repeatedly ({@code repeatLast=true}).
     */
    void programCdn(List<CdnResponse> responses) {
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode arr = body.putArray("responses");
        for (CdnResponse r : responses) {
            ObjectNode n = arr.addObject();
            n.put("status", r.status);
            if (r.body != null) {
                n.put("body", r.body);
            }
            if (r.delayMs > 0) {
                n.put("delayMs", r.delayMs);
            }
            ObjectNode headers = n.putObject("headers");
            r.headers.forEach(headers::put);
        }
        body.put("repeatLast", true);
        send("PUT", "/__control__/cdn/responses", body.toString());
    }

    /** Convenience: program a single sticky CDN response. */
    void programCdn(CdnResponse response) {
        programCdn(List.of(response));
    }

    /** Number of CDN requests received since the last {@link #reset()}. */
    int cdnRequestCount() {
        return cdnRequests().size();
    }

    /** CDN requests received since the last {@link #reset()}, in arrival order. */
    List<RecordedRequest> cdnRequests() {
        String json = send("GET", "/__control__/cdn/requests", null);
        try {
            JsonNode root = MAPPER.readTree(json);
            List<RecordedRequest> out = new ArrayList<>();
            for (JsonNode req : root.path("requests")) {
                Map<String, String> headers = new LinkedHashMap<>();
                JsonNode h = req.path("headers");
                h.fieldNames().forEachRemaining(name ->
                        headers.put(name.toLowerCase(), h.path(name).asText()));
                out.add(new RecordedRequest(
                        req.path("method").asText(),
                        req.path("path").asText(),
                        headers,
                        req.path("receivedAtMs").asLong()));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("failed to parse recorded CDN requests: " + json, e);
        }
    }

    private String send(String method, String path, String jsonBody) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10));
        if (jsonBody == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        try {
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                throw new IllegalStateException(
                        "control call " + method + " " + path + " failed: HTTP " + res.statusCode()
                                + " " + res.body());
            }
            return res.body();
        } catch (IOException e) {
            throw new IllegalStateException("control call " + method + " " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during control call " + method + " " + path, e);
        }
    }

    /** A single programmed CDN response. */
    static final class CdnResponse {
        final int status;
        final String body;                       // null => no body
        final Map<String, String> headers = new LinkedHashMap<>();
        int delayMs;                             // 0 => answer immediately

        private CdnResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }

        static CdnResponse of(int status, String body) {
            return new CdnResponse(status, body);
        }

        static CdnResponse status(int status) {
            return new CdnResponse(status, null);
        }

        CdnResponse header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        /**
         * Holds the response back this long before writing anything. The mock server records the
         * request at arrival, so a delayed response still shows up in cadence assertions at the
         * moment the provider issued it, and abandons the delay when the provider disconnects.
         */
        CdnResponse delay(Duration delay) {
            this.delayMs = (int) delay.toMillis();
            return this;
        }
    }

    /** A CDN request the backend recorded (header names lower-cased). */
    static final class RecordedRequest {
        final String method;
        final String path;
        final Map<String, String> headers;
        /** Unix epoch millis at arrival, stamped before any response delay. */
        final long receivedAtMs;

        RecordedRequest(String method, String path, Map<String, String> headers, long receivedAtMs) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.receivedAtMs = receivedAtMs;
        }

        boolean hasHeader(String name) {
            return headers.containsKey(name.toLowerCase());
        }

        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }
}
