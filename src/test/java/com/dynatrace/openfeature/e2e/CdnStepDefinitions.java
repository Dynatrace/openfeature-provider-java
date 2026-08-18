package com.dynatrace.openfeature.e2e;

import com.dynatrace.openfeature.DynatraceProvider;
import com.dynatrace.openfeature.DynatraceProviderOptions;
import com.dynatrace.openfeature.e2e.MockServer.CdnResponse;
import com.dynatrace.openfeature.e2e.MockServer.RecordedRequest;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderState;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step definitions for the CDN gherkin spec ({@code gherkin/cdn.feature}).
 *
 * <p>Black-box: the provider is embedded in an OpenFeature SDK instance rather than driven
 * directly. Each scenario binds a fresh {@link DynatraceProvider} to its own OpenFeature
 * {@code domain} via {@link OpenFeatureAPI#setProviderAndWait}, and every observation goes through
 * that SDK surface — flags are read with an OpenFeature {@link Client} and readiness is read from
 * {@link Client#getProviderState()} (fed by the provider's OpenFeature events). Every component the
 * provider talks to is emulated by the containerized {@link MockServer}, programmed via its HTTP control API.
 */
public class CdnStepDefinitions {

    private static final String BASE_KEY =
            "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5";

    /** Longer than the provider's 10s SSE-disconnected poll cadence, so a refresh is observed. */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(11);
    /** Considers time taken by all 5 retries for 5xx codes */
    private static final Duration RETRY_TIMEOUT = Duration.ofSeconds(2);
    /** Spans >2 poll cadences to prove fetching is suspended. */
    private static final Duration RATE_LIMIT_OBSERVE = Duration.ofSeconds(22);
    /** Window over which a "continues to" assertion must keep holding. */
    private static final Duration STABLE_WINDOW = Duration.ofSeconds(2);

    /** Gives every scenario a unique OpenFeature domain so providers never leak across scenarios. */
    private static final AtomicInteger SCENARIO_SEQ = new AtomicInteger();

    private final OpenFeatureAPI api = OpenFeatureAPI.getInstance();

    private String domain;
    private Client client;

    private String sdkKey;
    private DynatraceProviderOptions options;
    private MockServer mockServer;

    private DynatraceProvider provider;
    private Exception initError;

    // ---- lifecycle -------------------------------------------------------

    @Before
    public void setUp() {
        // A per-scenario domain isolates this scenario's provider on the shared OpenFeature API.
        domain = "e2e-test-" + SCENARIO_SEQ.incrementAndGet();
        client = api.getClient(domain);
        if (MockServer.isRunning()) {
            MockServer.get().reset();
        }
    }

    @After
    public void tearDown() {
        try {
            api.shutdown();
        } catch (RuntimeException ignored) {
            // best-effort cleanup
        }
    }

    // GIVEN

    @Given("a mock server is running")
    public void aMockServerIsRunning() {
        useMockServer();
    }

    @Given("a well-formed server SDK key")
    public void aWellFormedServerSdkKey() {
        sdkKey = BASE_KEY;
    }

    @Given("the SDK key {string}")
    public void theSdkKey(String key) {
        sdkKey = key;
    }

    @Given("the CDN serves the {string} flag configuration")
    public void theCdnServesTheFlagConfiguration(String flagFileName) {
        mockServer.programCdn(CdnResponse.of(200, config(flagFileName)));
    }

    @Given("an initialized, READY provider serving the {string} flag configuration")
    public void anInitializedReadyProviderServingTheFlagConfiguration(String flagFileName) {
        sdkKey = BASE_KEY;
        mockServer.programCdn(CdnResponse.of(200, config(flagFileName)));
        registerProviderWithSdk();
        assertEquals(ProviderState.READY, client.getProviderState());
    }

    @Given("an initialized, READY provider serving the {string} flag configuration with {string} header {string}")
    public void anInitializedReadyProviderServingTheFlagConfigurationWithHeader(String flagFileName, String header, String value) {
        sdkKey = BASE_KEY;
        mockServer.programCdn(CdnResponse.of(200, config(flagFileName)).header(header, value));
        registerProviderWithSdk();
        assertEquals(ProviderState.READY, client.getProviderState());
    }

    // WHEN

    @When("the provider is initialized")
    public void theProviderIsInitialized() {
        try {
            registerProviderWithSdk();
        } catch (Exception e) {
            // setProviderAndWait surfaces an initialization failure as an OpenFeatureError.
            initError = e;
        }
    }

    @When("the CDN responds with status {int}")
    public void theCdnRespondsWithStatus(int status) {
        mockServer.programCdn(CdnResponse.status(status));
    }

    @When("the CDN responds with status {int} and body {string}")
    public void theCdnRespondsWithStatusAndBody(int status, String body) {
        mockServer.programCdn(CdnResponse.of(status, body));
    }

    // Unquoted "null" in the outline denotes an absent body (no body at all), as opposed to the
    // empty-string body "" — {string} only matches quoted values, so this needs its own step.
    @When("the CDN responds with status {int} and body null")
    public void theCdnRespondsWithStatusAndBodyNull(int status) {
        mockServer.programCdn(CdnResponse.status(status));
    }

    @When("the CDN responds with status {int} and the {string} flag configuration with {string} header {string}")
    public void theCdnRespondsWithStatusAndTheFlagConfigurationWithHeader(int status, String flagFileName, String header, String value) {
        mockServer.programCdn(CdnResponse.of(status, config(flagFileName))
                .header(header, value));
    }

    @When("the CDN responds {int} then {int} with the {string} configuration")
    public void theCdnRespondsThenWithTheConfiguration(int first, int second, String flagFileName) {
        mockServer.programCdn(List.of(
                CdnResponse.status(first),
                CdnResponse.of(second, config(flagFileName))));
    }

    @When("the CDN responds with status {int} and Retry-After {int} seconds")
    public void theCdnRespondsWithStatusAndRetryAfterSeconds(int status, int seconds) {
        mockServer.programCdn(CdnResponse.status(status).header("Retry-After", String.valueOf(seconds)));
    }

    @When("polling triggers a configuration refetch")
    public void pollingTriggersAConfigurationRefetch() {
        int baseline = mockServer.cdnRequestCount();
        await(POLL_TIMEOUT, () -> mockServer.cdnRequestCount() > baseline);
    }

    // THEN

    @Then("initialization succeeds")
    public void initializationSucceeds() {
        assertNull(initError, () -> "expected init to succeed but got: " + initError);
    }

    @Then("initialization fails")
    public void initializationFails() {
        assertNotNull(initError, "expected initialization to fail");
    }

    @Then("the provider state is {string}")
    public void theProviderStateIs(String state) {
        assertEquals(ProviderState.valueOf(state), client.getProviderState());
    }

    @Then("the initial CDN request has no {string} header")
    public void theInitialCdnRequestHasNoHeader(String header) {
        assertFalse(firstCdnRequest().hasHeader(header),
                "initial request unexpectedly carried header: " + header);
    }

    @Then("the {}. CDN request has the {string} header {string}")
    public void theCdnRequestHasTheHeader(int index, String header, String value) {
        RecordedRequest request = nthCdnRequest(index-1);
        assertTrue(request.hasHeader(header),
                index + ". CDN request missing expected header: " + header);
        assertEquals(value, request.header(header),
                index + ". CDN request " + header + " header mismatch");
    }

    @Then("the initial CDN request returns {int}")
    public void theInitialCdnRequestReturns(int status) {
        // will remove the step from the spec, doesn't really make much sense since response code is programmed
    }

    @Then("the CDN received {int} requests")
    public void theCdnReceivedRequests(int count) {
        assertEquals(mockServer.cdnRequestCount(), count, "expected " + count + " CDN requests, saw " + mockServer.cdnRequestCount());
    }

    @Then("the CDN receives no further requests while rate-limited")
    public void theCdnReceivesNoFurtherRequestsWhileRateLimited() {
        int baseline = mockServer.cdnRequestCount();
        sleep(RATE_LIMIT_OBSERVE);
        assertEquals(baseline, mockServer.cdnRequestCount(),
                "provider issued CDN requests while it should have been rate-limited");
    }

    @Then("flag {string} evaluates to {word}")
    public void flagEvaluatesTo(String flag, String expected) {
        boolean want = Boolean.parseBoolean(expected);
        assertTrue(boolMatches(flag, want),"flag " + flag + " did not evaluate to " + expected);
    }

    @Then("flag {string} eventually evaluates to {word}")
    public void flagEventuallyEvaluatesTo(String flag, String expected) {
        boolean want = Boolean.parseBoolean(expected);
        await(RETRY_TIMEOUT, () -> boolMatches(flag, want));
    }

    @Then("flag {string} continues to evaluate to {word}")
    public void flagContinuesToEvaluateTo(String flag, String expected) {
        boolean want = Boolean.parseBoolean(expected);
        assertTrue(boolMatches(flag, want), "flag " + flag + " did not evaluate to " + expected);
        holdFor(STABLE_WINDOW, () -> boolMatches(flag, want),
                "flag " + flag + " changed away from " + expected);
    }

    @Then("evaluating flag {string} returns a Flag-Not-Found error")
    public void evaluatingFlagReturnsAFlagNotFoundError(String flag) {
        FlagEvaluationDetails<Boolean> details = client.getBooleanDetails(flag, false);
        assertEquals(ErrorCode.FLAG_NOT_FOUND, details.getErrorCode(),
                "expected FLAG_NOT_FOUND for unknown flag " + flag + ", got " + details.getErrorCode());
    }

    // ---- helpers ---------------------------------------------------------

    private void useMockServer() {
        if (mockServer == null) {
            mockServer = MockServer.get();
        }
        if (provider == null && options == null) {
            options = DynatraceProviderOptions.builder()
                    .configOrigin(mockServer.origin())
                    .metricsUrl(mockServer.metricsUrl())
                    .metricsFlushInterval(Duration.ofHours(1)) // no periodic metrics flush during a scenario
                    .disableSse()                              // this spec drives refreshes via polling
                    .build();
        }
    }

    /**
     * Embeds a freshly constructed provider in the OpenFeature SDK under this scenario's domain.
     * {@code setProviderAndWait} runs the provider's {@code initialize} and blocks until it reaches
     * READY, throwing an {@code OpenFeatureError} if initialization fails.
     */
    private void registerProviderWithSdk() {
        provider = new DynatraceProvider(sdkKey, options);
        api.setProviderAndWait(domain, provider);
    }

    private RecordedRequest firstCdnRequest() {
        return nthCdnRequest(0);
    }

    /** Returns the 0-indexed recorded CDN request, asserting at least that many were seen. */
    private RecordedRequest nthCdnRequest(int index) {
        List<RecordedRequest> requests = mockServer.cdnRequests();
        assertTrue(requests.size() > index,
                "expected at least " + (index + 1) + " CDN request(s), saw " + requests.size());
        return requests.get(index);
    }

    private boolean boolMatches(String flag, boolean expected) {
        // Read through the OpenFeature client; default to the opposite so a missing flag can never
        // masquerade as the expected value.
        return client.getBooleanValue(flag, !expected) == expected;
    }

    /** Directory holding the shared flag configuration fixtures from the test harness submodule. */
    private static final Path FLAGS_DIR = Path.of("fm-provider-test-harness", "flags");

    /**
     * Loads a flag configuration fixture from the test harness by its file name (extension omitted),
     * e.g. {@code "flags-v1"} -> {@code fm-provider-test-harness/flags/flags-v1.json}.
     */
    private static String config(String flagFileName) {
        Path file = FLAGS_DIR.resolve(flagFileName + ".json");
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read flag config fixture: " + file, e);
        }
    }

    private static void await(Duration timeout, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(100));
        }
        assertTrue(condition.getAsBoolean(), "condition not met within " + timeout.toSeconds() + "s");
    }

    private static void holdFor(Duration duration, BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + duration.toMillis();
        while (System.currentTimeMillis() < deadline) {
            assertTrue(condition.getAsBoolean(), message);
            sleep(Duration.ofMillis(100));
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
