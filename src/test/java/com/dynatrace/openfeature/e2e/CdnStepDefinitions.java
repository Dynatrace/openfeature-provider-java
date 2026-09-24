package com.dynatrace.openfeature.e2e;

import com.dynatrace.openfeature.DynatraceProvider;
import com.dynatrace.openfeature.DynatraceProviderOptions;
import com.dynatrace.openfeature.ProviderTestSupport;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step definitions for the CDN gherkin spec ({@code gherkin/cdn.feature}).
 *
 * <p>Black-box: the provider is embedded in an OpenFeature SDK instance rather than driven
 * directly. Each scenario binds a fresh {@link DynatraceProvider} to its own OpenFeature
 * {@code domain} via {@link OpenFeatureAPI#setProviderAndWait}, and every observation goes through
 * that SDK surface: flags are read with an OpenFeature {@link Client} and readiness is read from
 * {@link Client#getProviderState()} (fed by the provider's OpenFeature events). Every component the
 * provider talks to is emulated by the containerized {@link MockServer}, programmed via its HTTP control API.
 */
public class CdnStepDefinitions {

    private static final String BASE_KEY =
            "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5";

    /** The cadence the suite runs at. Read from the provider so scenarios never hard-code it. */
    private static final Duration POLL_INTERVAL = ProviderTestSupport.pollInterval();
    /** Bound on one CDN attempt, also read from the provider (KB section 2.1). */
    private static final Duration REQUEST_TIMEOUT = ProviderTestSupport.requestTimeout();

    /** Longer than the poll cadence, so a refresh is observed. */
    private static final Duration POLL_TIMEOUT = POLL_INTERVAL.plusSeconds(1);
    /** Considers time taken by all retries for 5xx codes */
    private static final Duration RETRY_TIMEOUT = Duration.ofSeconds(2);
    /** Window over which a "continues to" assertion must keep holding. */
    private static final Duration STABLE_WINDOW = Duration.ofSeconds(2);
    /** Window over which a state assertion may take to arrive, since state rides OpenFeature events. */
    private static final Duration STATE_TIMEOUT = Duration.ofSeconds(2);

    /** Slack added when sleeping to a tick boundary, so the tick has fired by the time we look. */
    private static final Duration TICK_MARGIN = Duration.ofMillis(500);
    /** Cap on waiting for expected requests to arrive; a fetch is at most two bounded attempts. */
    private static final Duration COUNT_TIMEOUT = REQUEST_TIMEOUT.multipliedBy(3);
    /** How long an exact request count must keep holding, so a stray extra request still fails. */
    private static final Duration COUNT_SETTLE = Duration.ofSeconds(1);
    /**
     * Tolerance on a measured interval. Wide enough for CI scheduling jitter, narrow enough that a
     * cadence anchored to the wrong moment (request completion, or initialization start) fails.
     */
    private static final Duration CADENCE_TOLERANCE = Duration.ofMillis(1500);
    /**
     * Requests closer together than this belong to the same poll tick: a tick that retries issues
     * its second attempt one request timeout later, which is well inside half a poll interval.
     */
    private static final long SAME_TICK_GAP_MS = POLL_INTERVAL.toMillis() / 2;
    /** How far before a tick an SSE re-fetch is triggered, so it is still in flight when the tick fires. */
    private static final Duration SHORTLY_BEFORE = Duration.ofMillis(500);

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

    /**
     * Baseline for "further request" counts. Set when the provider is arranged, and again by every
     * step that advances the clock, so each count reads as "since the last thing that happened".
     */
    private int countBaseline;

    /**
     * When initialization returned, success or failure. The polling timer starts there (KB section
     * 3.1 step 4), so it is also the anchor every tick boundary is derived from.
     */
    private long pollingStartedAtMs;

    /** The CDN response sequence currently programmed, so a delay can be layered onto it. */
    private List<CdnResponse> programmed = List.of();

    /** The Retry-After the CDN was programmed with, so a scenario can wait that window out. */
    private Duration retryAfter = Duration.ZERO;

    // ---- lifecycle -------------------------------------------------------

    @Before
    public void setUp() {
        // A per-scenario domain isolates this scenario's provider on the shared OpenFeature API.
        domain = "e2e-test-" + SCENARIO_SEQ.incrementAndGet();
        client = api.getClient(domain);
        if (MockServer.isRunning()) {
            MockServer.get().reset();
        }
        countBaseline = 0;
        programmed = List.of();
        retryAfter = Duration.ZERO;
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

    @Given("the SDK key {string}")
    public void theSdkKey(String key) {
        sdkKey = key;
    }

    @Given("the CDN serves the {string} flag configuration")
    public void theCdnServesTheFlagConfiguration(String flagFileName) {
        program(CdnResponse.of(200, config(flagFileName)));
    }

    @Given("an initialized, READY provider serving the {string} flag configuration")
    public void anInitializedReadyProviderServingTheFlagConfiguration(String flagFileName) {
        sdkKey = BASE_KEY;
        program(CdnResponse.of(200, config(flagFileName)));
        registerProviderWithSdk();
        assertEquals(ProviderState.READY, client.getProviderState());
        markArranged();
    }

    @Given("an initialized, READY provider serving the {string} flag configuration with {string} header {string}")
    public void anInitializedReadyProviderServingTheFlagConfigurationWithHeader(String flagFileName, String header, String value) {
        sdkKey = BASE_KEY;
        program(CdnResponse.of(200, config(flagFileName)).header(header, value));
        registerProviderWithSdk();
        assertEquals(ProviderState.READY, client.getProviderState());
        markArranged();
    }

    /**
     * Makes every programmed response outlive a single attempt, so a fetch burns its whole budget
     * (two attempts, each abandoned at the request timeout) and is still running when the next
     * tick fires.
     */
    @Given("the CDN responds slower than the request timeout")
    public void theCdnRespondsSlowerThanTheRequestTimeout() {
        delayProgrammedResponses(REQUEST_TIMEOUT.multipliedBy(4));
    }

    /**
     * Makes a fetch take real time without ever timing out, so the gap between a fetch starting
     * and completing is large enough to tell the two candidate cadence anchors apart.
     */
    @Given("the CDN responds slowly but within the request timeout")
    public void theCdnRespondsSlowlyButWithinTheRequestTimeout() {
        delayProgrammedResponses(REQUEST_TIMEOUT.minusSeconds(1));
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
        // Anchor, not baseline: a scenario with no arrangement step counts from its own start, so
        // the requests initialization made are part of what it asserts on.
        pollingStartedAtMs = System.currentTimeMillis();
    }

    @When("the CDN responds with status {int}")
    public void theCdnRespondsWithStatus(int status) {
        program(CdnResponse.status(status));
    }

    @When("the CDN responds with status {int} and body {string}")
    public void theCdnRespondsWithStatusAndBody(int status, String body) {
        program(CdnResponse.of(status, body));
    }

    // Unquoted "null" in the outline denotes an absent body (no body at all), as opposed to the
    // empty-string body "": {string} only matches quoted values, so this needs its own step.
    @When("the CDN responds with status {int} and body null")
    public void theCdnRespondsWithStatusAndBodyNull(int status) {
        program(CdnResponse.status(status));
    }

    @When("the CDN responds with status {int} and the {string} flag configuration with {string} header {string}")
    public void theCdnRespondsWithStatusAndTheFlagConfigurationWithHeader(int status, String flagFileName, String header, String value) {
        program(CdnResponse.of(status, config(flagFileName)).header(header, value));
    }

    @When("the CDN responds {int} then {int} with the {string} configuration")
    public void theCdnRespondsThenWithTheConfiguration(int first, int second, String flagFileName) {
        program(List.of(
                CdnResponse.status(first),
                CdnResponse.of(second, config(flagFileName))));
    }

    @When("the CDN responds with status {int} and Retry-After {int} seconds")
    public void theCdnRespondsWithStatusAndRetryAfterSeconds(int status, int seconds) {
        retryAfter = Duration.ofSeconds(seconds);
        program(CdnResponse.status(status).header("Retry-After", String.valueOf(seconds)));
    }

    @When("polling triggers a configuration refetch")
    public void pollingTriggersAConfigurationRefetch() {
        int baseline = mockServer.cdnRequestCount();
        await(POLL_TIMEOUT, () -> mockServer.cdnRequestCount() > baseline);
    }

    /**
     * Waits out whole poll intervals. The baseline is taken before sleeping, so the assertion that
     * follows counts exactly the requests these intervals produced.
     */
    @When("{int} poll interval(s) elapse(s)")
    public void pollIntervalsElapse(int intervals) {
        resetCountBaseline();
        sleepUntil(nextTickAtMs() + (intervals - 1) * POLL_INTERVAL.toMillis() + TICK_MARGIN.toMillis());
    }

    /**
     * Waits for the next cadence-anchored tick specifically, rather than for a duration. This is
     * the distinction a Retry-After window turns on: the window expiring does not itself fetch.
     */
    @When("the next poll tick arrives")
    public void theNextPollTickArrives() {
        resetCountBaseline();
        sleepUntil(nextTickAtMs() + TICK_MARGIN.toMillis());
    }

    @When("the Retry-After window expires")
    public void theRetryAfterWindowExpires() {
        resetCountBaseline();
        sleep(retryAfter.plus(TICK_MARGIN));
    }

    /**
     * Occupies the fetch slot just before a tick, so the tick has something to contend with. The
     * re-fetch is requested directly rather than over a live SSE stream: opening one would also
     * move the provider onto the SSE-connected cadence and change the timing under test.
     */
    @When("an SSE re-fetch is triggered shortly before the next poll tick")
    public void anSseRefetchIsTriggeredShortlyBeforeTheNextPollTick() {
        sleepUntil(nextTickAtMs() - SHORTLY_BEFORE.toMillis());
        int before = mockServer.cdnRequestCount();
        ProviderTestSupport.triggerSseRefetch(provider, null);
        // The re-fetch runs on the polling worker, so wait for its request to actually reach the
        // CDN. Otherwise it lands after the next step takes its baseline and is miscounted as a
        // request the contended tick made.
        await(SHORTLY_BEFORE, () -> mockServer.cdnRequestCount() > before);
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
        // Provider state reaches the client over an OpenFeature event, so allow it a moment to
        // land; a state that is already correct passes on the first check.
        ProviderState want = ProviderState.valueOf(state);
        await(STATE_TIMEOUT, () -> client.getProviderState() == want);
        assertEquals(want, client.getProviderState());
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

    /**
     * Counts CDN requests since the baseline, which is the arrangement step, the scenario start, or
     * whichever clock-advancing step ran most recently.
     *
     * <p>The count must settle rather than merely be reached: the interesting assertions here are
     * upper bounds (a fetch that must not make a third attempt, a tick that must not fetch at all),
     * and a bound only holds if it keeps holding.
     */
    @Then("the CDN has received {int} further request(s)")
    public void theCdnHasReceivedFurtherRequests(int count) {
        if (count > 0) {
            await(COUNT_TIMEOUT, () -> furtherRequestCount() >= count);
        }
        holdFor(COUNT_SETTLE, () -> furtherRequestCount() == count,
                () -> "expected " + count + " further CDN request(s), saw " + furtherRequestCount());
        resetCountBaseline();
    }

    /**
     * Asserts the cadence is anchored to when each fetch was issued, not to when it finished, by
     * measuring the gap between the first request of consecutive ticks. A tick that retries issues
     * two requests; those are grouped back onto the tick that made them.
     */
    @Then("consecutive poll ticks are one poll interval apart")
    public void consecutivePollTicksAreOnePollIntervalApart() {
        List<Long> ticks = tickTimes();
        assertTrue(ticks.size() >= 2,
                "need at least 2 poll ticks to compare, saw " + ticks.size());
        for (int i = 1; i < ticks.size(); i++) {
            assertCadence(ticks.get(i) - ticks.get(i - 1),
                    "gap between poll tick " + i + " and " + (i + 1));
        }
    }

    /**
     * Asserts the polling timer starts once initialization has finished, not when it began. With a
     * slow but successful initial fetch the two differ by the fetch duration, which is what makes
     * the wrong anchor observable.
     */
    @Then("the first poll tick is one poll interval after initialization completed")
    public void theFirstPollTickIsOnePollIntervalAfterInitializationCompleted() {
        long anchor = pollingStartedAtMs;
        await(POLL_INTERVAL.plus(COUNT_TIMEOUT), () -> firstRequestAfter(anchor) != null);
        Long firstTick = firstRequestAfter(anchor);
        assertNotNull(firstTick, "no poll tick observed after initialization completed");
        assertCadence(firstTick - anchor, "gap between initialization completing and the first poll tick");
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

    /** Programs the CDN and remembers the sequence, so a later step can layer a delay onto it. */
    private void program(List<CdnResponse> responses) {
        programmed = List.copyOf(responses);
        mockServer.programCdn(programmed);
    }

    private void program(CdnResponse response) {
        program(List.of(response));
    }

    private void delayProgrammedResponses(Duration delay) {
        assertFalse(programmed.isEmpty(), "no CDN response programmed to delay");
        programmed.forEach(r -> r.delay(delay));
        mockServer.programCdn(programmed);
    }

    /** Marks the end of a scenario's arrangement: counts and tick boundaries run from here. */
    private void markArranged() {
        pollingStartedAtMs = System.currentTimeMillis();
        resetCountBaseline();
    }

    private void resetCountBaseline() {
        countBaseline = mockServer.cdnRequestCount();
    }

    private int furtherRequestCount() {
        return mockServer.cdnRequestCount() - countBaseline;
    }

    /** Next tick boundary on the cadence anchored at {@link #pollingStartedAtMs}. */
    private long nextTickAtMs() {
        long period = POLL_INTERVAL.toMillis();
        long elapsed = System.currentTimeMillis() - pollingStartedAtMs;
        return pollingStartedAtMs + (elapsed / period + 1) * period;
    }

    /** Arrival time of the first CDN request after {@code epochMs}, or null if there is none yet. */
    private Long firstRequestAfter(long epochMs) {
        return mockServer.cdnRequests().stream()
                .map(r -> r.receivedAtMs)
                .filter(at -> at > epochMs)
                .findFirst()
                .orElse(null);
    }

    /**
     * Arrival times of the first request of each poll tick. Requests less than half a poll interval
     * apart are the attempts of one tick (a retry follows one request timeout later), so only the
     * first of each run is a tick.
     */
    private List<Long> tickTimes() {
        List<Long> ticks = new ArrayList<>();
        long previous = Long.MIN_VALUE;
        for (RecordedRequest request : mockServer.cdnRequests()) {
            if (previous == Long.MIN_VALUE || request.receivedAtMs - previous >= SAME_TICK_GAP_MS) {
                ticks.add(request.receivedAtMs);
            }
            previous = request.receivedAtMs;
        }
        return ticks;
    }

    private static void assertCadence(long measuredMs, String what) {
        long expected = POLL_INTERVAL.toMillis();
        assertTrue(Math.abs(measuredMs - expected) <= CADENCE_TOLERANCE.toMillis(),
                what + " was " + measuredMs + "ms, expected " + expected + "ms +/- "
                        + CADENCE_TOLERANCE.toMillis() + "ms");
    }

    private void useMockServer() {
        if (mockServer == null) {
            mockServer = MockServer.get();
        }
        if (provider == null && options == null) {
            options = DynatraceProviderOptions.builder()
                    .configOrigin(mockServer.origin())
                    .metricsOrigin(mockServer.origin())
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
        holdFor(duration, condition, () -> message);
    }

    private static void holdFor(Duration duration, BooleanSupplier condition, Supplier<String> message) {
        long deadline = System.currentTimeMillis() + duration.toMillis();
        while (System.currentTimeMillis() < deadline) {
            assertTrue(condition.getAsBoolean(), message);
            sleep(Duration.ofMillis(50));
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    private static void sleepUntil(long epochMs) {
        long remaining = epochMs - System.currentTimeMillis();
        if (remaining > 0) {
            sleep(Duration.ofMillis(remaining));
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
