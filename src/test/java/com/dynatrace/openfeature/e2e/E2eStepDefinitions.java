package com.dynatrace.openfeature.e2e;

import com.dynatrace.openfeature.DynatraceProvider;
import com.dynatrace.openfeature.DynatraceProviderOptions;
import com.dynatrace.openfeature.ProviderTestSupport;
import com.dynatrace.openfeature.e2e.MockServer.CdnResponse;
import com.dynatrace.openfeature.e2e.MockServer.RecordedRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EventDetails;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvent;
import dev.openfeature.sdk.ProviderState;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step definitions for the shared gherkin specs ({@code gherkin/}).
 *
 * <p>Black-box: the provider is embedded in an OpenFeature SDK instance rather than driven
 * directly. Each scenario binds a fresh {@link DynatraceProvider} to its own OpenFeature
 * {@code domain} via {@link OpenFeatureAPI#setProviderAndWait}, and every observation goes through
 * that SDK surface: flags are read with an OpenFeature {@link Client} and readiness is read from
 * {@link Client#getProviderState()} (fed by the provider's OpenFeature events). Every component the
 * provider talks to is emulated by the containerized {@link MockServer}, configured via its HTTP control API.
 * SSE connectivity and poll cadence are asserted via the mock server's subscriber count and CDN
 * request log.
 */
public class E2eStepDefinitions {

    private static final String BASE_KEY =
            "dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5";

    /** The provider's real cadence; SSE is off in the CDN spec. */
    private static final Duration POLL_INTERVAL = ProviderTestSupport.SSE_DISCONNECTED_POLL_INTERVAL;
    /** Bound on one CDN attempt. */
    private static final Duration REQUEST_TIMEOUT = ProviderTestSupport.requestTimeout();

    /** Longer than the poll cadence, so a refresh is observed. */
    private static final Duration POLL_TIMEOUT = POLL_INTERVAL.plusSeconds(1);
    /** Covers all retries for 5xx codes. */
    private static final Duration RETRY_TIMEOUT = Duration.ofSeconds(2);
    /** How long a "continues to" assertion must keep holding. */
    private static final Duration STABLE_WINDOW = Duration.ofSeconds(2);
    /** Slack for something the step just caused, and the window for asserting its absence. */
    private static final Duration SETTLE = Duration.ofSeconds(2);
    /** How long to wait for a provider event the step just caused. */
    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(5);
    /** How long to wait for the provider to open its SSE stream. */
    private static final Duration SSE_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** Generous, since the event source's reconnect backoff grows with each failed attempt. */
    private static final Duration SSE_RECONNECT_TIMEOUT = Duration.ofSeconds(45);
    /** Placeholder for the mock server's authority in a spec'd URL. */
    private static final String MOCK_SERVER_PLACEHOLDER = "<mockserver>";
    /** Slack past a tick boundary, so the tick has fired by the time we look. */
    private static final Duration TICK_MARGIN = Duration.ofMillis(500);
    /** Cap on waiting for expected requests; a fetch is at most two bounded attempts. */
    private static final Duration COUNT_TIMEOUT = REQUEST_TIMEOUT.multipliedBy(3);
    /** How long an exact request count must keep holding. */
    private static final Duration COUNT_SETTLE = Duration.ofSeconds(1);
    /** Tolerance on a measured interval: absorbs CI jitter, still catches a wrongly anchored cadence. */
    private static final Duration CADENCE_TOLERANCE = Duration.ofMillis(1500);
    /** Requests closer together than this belong to the same poll tick (i.e. are retries). */
    private static final long SAME_TICK_GAP_MS = POLL_INTERVAL.toMillis() / 2;
    /** How far before a tick an SSE re-fetch is triggered, so it is still in flight then. */
    private static final Duration SHORTLY_BEFORE = Duration.ofMillis(500);

    /** Gives every scenario a unique OpenFeature domain. */
    private static final AtomicInteger SCENARIO_SEQ = new AtomicInteger();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpenFeatureAPI api = OpenFeatureAPI.getInstance();

    /** Provider events seen on this scenario's client, in arrival order. */
    private final List<RecordedEvent> events = Collections.synchronizedList(new ArrayList<>());
    /** Index into {@link #events}; each "a PROVIDER_* event is emitted" step consumes one. */
    private int eventCursor;

    private String domain;
    private Client client;

    private String sdkKey;
    private DynatraceProviderOptions.Builder optionsBuilder;
    private boolean sseEnabled;
    private MockServer mockServer;

    private DynatraceProvider provider;
    private Exception initError;

    /** Baseline for "further request" counts; reset by arrangement and every clock-advancing step. */
    private int countBaseline;
    /** When initialization returned; the anchor every poll tick boundary is derived from. */
    private long pollingStartedAtMs;

    /** The CDN responses currently set on the mock, so a delay can be layered onto them. */
    private List<CdnResponse> cdnResponses = List.of();

    /** The Retry-After the CDN was set up with, so a scenario can wait that window out. */
    private Duration retryAfter = Duration.ZERO;

    @Before
    public void setUp() {
        // A per-scenario domain isolates this scenario's provider on the shared OpenFeature API.
        domain = "e2e-test-" + SCENARIO_SEQ.incrementAndGet();
        client = api.getClient(domain);
        for (ProviderEvent event : ProviderEvent.values()) {
            client.on(event, details -> events.add(new RecordedEvent(event, details)));
        }
        if (MockServer.isRunning()) {
            MockServer server = MockServer.get();
            server.reset();
            // reset() clears the SSE outage gate but not a stream the previous scenario's provider
            // is still tearing down; wait it out so "opens no SSE connection" sees a clean slate.
            pollUntil(SETTLE, () -> server.sseClientCount() == 0);
        }
        countBaseline = 0;
        cdnResponses = List.of();
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
        useMockServer(false);
    }

    @Given("a mock server with SSE enabled is running")
    public void aMockServerWithSseEnabledIsRunning() {
        useMockServer(true);
    }

    @Given("the SDK key {string}")
    public void theSdkKey(String key) {
        sdkKey = key;
    }

    @Given("SSE is disabled by configuration")
    public void sseIsDisabledByConfiguration() {
        optionsBuilder.disableSse();
        sseEnabled = false;
    }

    @Given("the CDN serves the {string} flag configuration")
    public void theCdnServesTheFlagConfiguration(String flagFileName) {
        setCdnResponses(CdnResponse.of(200, loadFlagConfig(flagFileName)));
    }

    @Given("the CDN serves the {string} flag configuration advertising an SSE stream at url {string}")
    public void theCdnServesTheFlagConfigurationAdvertisingAnSseStreamAtUrl(String flagFileName, String url) {
        setCdnResponses(CdnResponse.of(200, advertisingSseUrl(loadFlagConfig(flagFileName), url)));
    }

    // header and value are null when the step has no "with ... header ..." suffix
    @Given("^an initialized, READY provider serving the \"([^\"]*)\" flag configuration(?: with \"([^\"]*)\" header \"([^\"]*)\")?$")
    public void anInitializedReadyProviderServingTheFlagConfiguration(String flagFileName, String header, String value) {
        sdkKey = BASE_KEY;
        CdnResponse response = CdnResponse.of(200, loadFlagConfig(flagFileName));
        setCdnResponses(header == null ? response : response.withHeader(header, value));
        registerProviderWithSdk();
        assertEquals(ProviderState.READY, client.getProviderState());
        awaitSseSettled();
        markArranged();
    }

    /** Outlives a whole fetch (two attempts, each abandoned at the timeout), so it spans a tick. */
    @Given("the CDN responds slower than the request timeout")
    public void theCdnRespondsSlowerThanTheRequestTimeout() {
        delayCdnResponses(REQUEST_TIMEOUT.multipliedBy(4));
    }

    /** Takes real time without timing out, so fetch start and fetch end are far enough apart to tell apart. */
    @Given("the CDN responds slowly but within the request timeout")
    public void theCdnRespondsSlowlyButWithinTheRequestTimeout() {
        delayCdnResponses(REQUEST_TIMEOUT.minusSeconds(1));
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
        setCdnResponses(CdnResponse.of(status));
    }

    // unquoted null is an absent body, as opposed to the empty-string body ""
    @When("^the CDN responds with status (\\d+) and body (\"[^\"]*\"|null)$")
    public void theCdnRespondsWithStatusAndBody(int status, String body) {
        setCdnResponses("null".equals(body)
                ? CdnResponse.of(status)
                : CdnResponse.of(status, body.substring(1, body.length() - 1)));
    }

    @When("the CDN responds with status {int} and the {string} flag configuration with {string} header {string}")
    public void theCdnRespondsWithStatusAndTheFlagConfigurationWithHeader(int status, String flagFileName, String header, String value) {
        setCdnResponses(CdnResponse.of(status, loadFlagConfig(flagFileName)).withHeader(header, value));
    }

    @When("the CDN responds {int} then {int} with the {string} configuration")
    public void theCdnRespondsThenWithTheConfiguration(int first, int second, String flagFileName) {
        setCdnResponses(List.of(
                CdnResponse.of(first),
                CdnResponse.of(second, loadFlagConfig(flagFileName))));
    }

    @When("the CDN responds with status {int} and Retry-After {int} seconds")
    public void theCdnRespondsWithStatusAndRetryAfterSeconds(int status, int seconds) {
        retryAfter = Duration.ofSeconds(seconds);
        setCdnResponses(CdnResponse.of(status).withHeader("Retry-After", String.valueOf(seconds)));
    }

    @When("polling triggers a configuration refetch")
    public void pollingTriggersAConfigurationRefetch() {
        awaitCdnRequestWithin(POLL_TIMEOUT);
    }

    @When("polling triggers a configuration refetch after the SSE_DISCONNECTED_POLL_INTERVAL")
    public void pollingTriggersAConfigurationRefetchAfterTheSseDisconnectedPollInterval() {
        awaitCdnRequestWithin(ProviderTestSupport.SSE_DISCONNECTED_POLL_INTERVAL);
    }

    @When("polling triggers a configuration refetch after the SSE_CONNECTED_POLL_INTERVAL")
    public void pollingTriggersAConfigurationRefetchAfterTheSseConnectedPollInterval() {
        awaitCdnRequestWithin(ProviderTestSupport.SSE_CONNECTED_POLL_INTERVAL);
    }

    /**
     * A blip: the backend drops the stream and refuses connections for {@code outageSeconds}, then accepts them again.
     */
    @When("the SSE stream drops for {int} seconds and reconnects within the DISCONNECT_DEBOUNCE_TIME_WINDOW")
    public void theSseStreamDropsForSecondsAndReconnectsWithinTheDebounceWindow(int outageSeconds) {
        Duration debounce = ProviderTestSupport.DISCONNECT_DEBOUNCE_TIME_WINDOW;
        mockServer.dropSse(Duration.ofSeconds(outageSeconds));
        assertTrue(pollUntil(debounce, () -> mockServer.sseClientCount() >= 1),
                "SSE did not reconnect within the " + debounce.toSeconds() + "s disconnect debounce window");
    }

    /**
     * A sustained outage: connections stay refused until the stream is restored or reset.
     */
    @When("the SSE stream drops and stays down past the DISCONNECT_DEBOUNCE_TIME_WINDOW")
    public void theSseStreamDropsAndStaysDownPastTheDebounceWindow() {
        mockServer.dropSse();
        // Hand the provider its debounce window (plus slack) to escalate the fault to a disconnect;
        // the following Then steps assert what that escalation did.
        pollUntil(ProviderTestSupport.DISCONNECT_DEBOUNCE_TIME_WINDOW.plus(SETTLE),
                () -> client.getProviderState() != ProviderState.READY);
    }

    @When("the SSE stream reconnects")
    public void theSseStreamReconnects() {
        mockServer.restoreSse();
        assertTrue(pollUntil(SSE_RECONNECT_TIMEOUT, () -> mockServer.sseClientCount() >= 1),
                "SSE did not reconnect after the outage was lifted");
    }

    @When("^the server emits an SSE message (.*)$")
    public void theServerEmitsAnSseMessage(String rawPayload) {
        String payload = unquote(rawPayload);
        int baseline = mockServer.countCdnRequests();
        mockServer.emitSse(payload);
        // essentially a sleep but stop earlier if received message has triggered cdn request
        pollUntil(SETTLE, () -> mockServer.countCdnRequests() > baseline);
    }

    @When("the grace period expires")
    public void theGracePeriodExpires() {
        pollUntil(ProviderTestSupport.GRACE_PERIOD.plus(SETTLE), () -> client.getProviderState() == ProviderState.ERROR);
    }

    @When("the provider is shut down")
    public void theProviderIsShutDown() {
        api.shutdown();
    }

    /** Baseline is taken before sleeping, so the next assertion counts what these intervals produced. */
    @When("{int} poll interval(s) elapse(s)")
    public void pollIntervalsElapse(int intervals) {
        resetCountBaseline();
        sleepUntil(computeNextTickAtMs() + (intervals - 1) * POLL_INTERVAL.toMillis() + TICK_MARGIN.toMillis());
    }

    /**
     * Waits for the next cadence-anchored tick specifically, rather than for a duration. This is
     * the distinction a Retry-After window turns on: the window expiring does not itself fetch.
     */
    @When("the next poll tick arrives")
    public void theNextPollTickArrives() {
        resetCountBaseline();
        sleepUntil(computeNextTickAtMs() + TICK_MARGIN.toMillis());
    }

    @When("the Retry-After window expires")
    public void theRetryAfterWindowExpires() {
        resetCountBaseline();
        sleep(retryAfter.plus(TICK_MARGIN));
    }

    /** Occupies the fetch slot just before a tick, so the tick has something to contend with. */
    @When("an SSE re-fetch is triggered shortly before the next poll tick")
    public void anSseRefetchIsTriggeredShortlyBeforeTheNextPollTick() {
        sleepUntil(computeNextTickAtMs() - SHORTLY_BEFORE.toMillis());
        int before = mockServer.countCdnRequests();
        ProviderTestSupport.requestOutOfBandFetch(provider, null);
        // The re-fetch runs on the polling worker, so wait for its request to actually reach the
        // CDN. Otherwise it lands after the next step takes its baseline and is miscounted as a
        // request the contended tick made.
        waitFor(SHORTLY_BEFORE, () -> mockServer.countCdnRequests() > before);
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
        waitFor(SETTLE, () -> client.getProviderState() == want);
        assertEquals(want, client.getProviderState());
    }

    @Then("a {word} event is emitted")
    public void aProviderEventIsEmitted(String eventName) {
        consumeEvent(ProviderEvent.valueOf(eventName));
    }

    @Then("a PROVIDER_CONFIGURATION_CHANGED event is emitted with changed flag {string}")
    public void aConfigurationChangedEventIsEmittedWithChangedFlag(String flag) {
        EventDetails details = consumeEvent(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED);
        List<String> changed = details.getFlagsChanged();
        assertNotNull(changed, "PROVIDER_CONFIGURATION_CHANGED carried no changed flags");
        assertTrue(changed.contains(flag),
                "PROVIDER_CONFIGURATION_CHANGED changed flags " + changed + " do not include " + flag);
    }

    @Then("the provider has an SSE connection to {string}")
    public void theProviderHasAnSseConnectionTo(String path) {
        // The backend serves a single stream path, so a live subscriber is a stream to that path.
        assertEquals(MockServer.SSE_PATH, path,
                "the mock server only serves an SSE stream at " + MockServer.SSE_PATH);
        assertTrue(pollUntil(SSE_CONNECT_TIMEOUT, () -> mockServer.sseClientCount() == 1),
                "provider did not open an SSE connection to " + path);
    }

    @Then("the provider opens no SSE connection")
    public void theProviderOpensNoSseConnection() {
        // Hold the assertion: a connection attempt in flight must not be mistaken for none at all.
        assertHoldsFor(SETTLE, () -> mockServer.sseClientCount() == 0,
                "provider unexpectedly opened an SSE connection");
    }

    @Then("the provider closes the SSE connection")
    public void theProviderClosesTheSseConnection() {
        assertTrue(pollUntil(SETTLE, () -> mockServer.sseClientCount() == 0),
                "provider did not close the SSE connection");
    }

    @Then("a message after shutdown triggers no CDN request")
    public void aMessageAfterShutdownTriggersNoCdnRequest() {
        int baseline = mockServer.countCdnRequests();
        mockServer.emitSse("{\"type\":\"refetchConfig\",\"lastModified\":1704153600}");
        sleep(STABLE_WINDOW);
        assertEquals(baseline, mockServer.countCdnRequests(),
                "a shut-down provider still re-fetched on an SSE message");
    }

    /**
     * The cadence itself isn't observable black-box, so it is asserted by its consequence: with SSE
     * connected no fetch may arrive for longer than the aggressive cadence would allow.
     */
    @Then("the active poll interval is the SSE_CONNECTED_POLL_INTERVAL")
    public void theActivePollIntervalIsTheSseConnectedInterval() {
        Duration interval = ProviderTestSupport.SSE_CONNECTED_POLL_INTERVAL;
        assertTrue(POLL_TIMEOUT.compareTo(interval) < 0,
                "observation window must be shorter than the " + interval.toSeconds() + "s interval under test");
        // The connect catch-up fetch must be in before the baseline, or it lands mid-observation.
        awaitSseSettled();
        int baseline = mockServer.countCdnRequests();
        sleep(POLL_TIMEOUT);
        assertEquals(baseline, mockServer.countCdnRequests(),
                "provider kept fetching within " + POLL_TIMEOUT.toSeconds()
                        + "s, so polling did not relax to the " + interval.toSeconds() + "s interval");
    }

    /**
     * Asserted by its consequence too: a fetch must arrive within the aggressive cadence.
     */
    @Then("the active poll interval is the SSE_DISCONNECTED_POLL_INTERVAL")
    public void theActivePollIntervalIsTheSseDisconnectedInterval() {
        awaitCdnRequestWithin(ProviderTestSupport.SSE_DISCONNECTED_POLL_INTERVAL);
    }

    @Then("the initial CDN request has no {string} header")
    public void theInitialCdnRequestHasNoHeader(String header) {
        assertFalse(getFirstCdnRequest().hasHeader(header),
                "initial request unexpectedly carried header: " + header);
    }

    @Then("the {}. CDN request has the {string} header {string}")
    public void theCdnRequestHasTheHeader(int index, String header, String value) {
        RecordedRequest request = getNthCdnRequest(index-1);
        assertTrue(request.hasHeader(header),
                index + ". CDN request missing expected header: " + header);
        assertEquals(value, request.getHeader(header),
                index + ". CDN request " + header + " header mismatch");
    }

    @Then("the CDN received {int} requests")
    public void theCdnReceivedRequests(int count) {
        // A fetch the provider was told to make (SSE connect, SSE message, poll) is in flight by the
        // time this step runs, so wait for the expected count before insisting on it.
        pollUntil(SETTLE, () -> mockServer.countCdnRequests() >= count);
        assertEquals(count, mockServer.countCdnRequests(),
                "expected " + count + " CDN requests, saw " + mockServer.countCdnRequests());
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
            waitFor(COUNT_TIMEOUT, () -> countFurtherRequests() >= count);
        }
        assertHoldsFor(COUNT_SETTLE, () -> countFurtherRequests() == count,
                () -> "expected " + count + " further CDN request(s), saw " + countFurtherRequests());
        resetCountBaseline();
    }

    /**
     * Asserts the cadence is anchored to when each fetch was issued, not to when it finished, by
     * measuring the gap between the first request of consecutive ticks.
     */
    @Then("consecutive poll ticks are one poll interval apart")
    public void consecutivePollTicksAreOnePollIntervalApart() {
        List<Long> ticks = collectTickTimes();
        assertTrue(ticks.size() >= 2,
                "need at least 2 poll ticks to compare, saw " + ticks.size());
        for (int i = 1; i < ticks.size(); i++) {
            assertCadence(ticks.get(i) - ticks.get(i - 1),
                    "gap between poll tick " + i + " and " + (i + 1));
        }
    }

    /**
     * Asserts the polling timer starts once initialization has finished, not when it began. A slow
     * initial fetch is what separates the two anchors enough to tell them apart.
     */
    @Then("the first poll tick is one poll interval after initialization completed")
    public void theFirstPollTickIsOnePollIntervalAfterInitializationCompleted() {
        long anchor = pollingStartedAtMs;
        waitFor(POLL_INTERVAL.plus(COUNT_TIMEOUT), () -> findFirstRequestAfter(anchor) != null);
        Long firstTick = findFirstRequestAfter(anchor);
        assertNotNull(firstTick, "no poll tick observed after initialization completed");
        assertCadence(firstTick - anchor, "gap between initialization completing and the first poll tick");
    }

    @Then("flag {string} evaluates to {word}")
    public void flagEvaluatesTo(String flag, String expected) {
        boolean want = Boolean.parseBoolean(expected);
        assertTrue(isFlagValue(flag, want),"flag " + flag + " did not evaluate to " + expected);
    }

    @Then("flag {string} eventually evaluates to {word}")
    public void flagEventuallyEvaluatesTo(String flag, String expected) {
        boolean want = Boolean.parseBoolean(expected);
        waitFor(RETRY_TIMEOUT, () -> isFlagValue(flag, want));
    }

    @Then("flag {string} continues to evaluate to {word}")
    public void flagContinuesToEvaluateTo(String flag, String expected) {
        boolean want = Boolean.parseBoolean(expected);
        assertTrue(isFlagValue(flag, want), "flag " + flag + " did not evaluate to " + expected);
        assertHoldsFor(STABLE_WINDOW, () -> isFlagValue(flag, want),
                "flag " + flag + " changed away from " + expected);
    }

    @Then("evaluating flag {string} returns a Flag-Not-Found error")
    public void evaluatingFlagReturnsAFlagNotFoundError(String flag) {
        FlagEvaluationDetails<Boolean> details = client.getBooleanDetails(flag, false);
        assertEquals(ErrorCode.FLAG_NOT_FOUND, details.getErrorCode(),
                "expected FLAG_NOT_FOUND for unknown flag " + flag + ", got " + details.getErrorCode());
    }

    /** Remembers the sequence too, so a later step can layer a delay onto it. */
    private void setCdnResponses(List<CdnResponse> responses) {
        cdnResponses = List.copyOf(responses);
        mockServer.setCdnResponses(cdnResponses);
    }

    private void setCdnResponses(CdnResponse response) {
        setCdnResponses(List.of(response));
    }

    private void delayCdnResponses(Duration delay) {
        assertFalse(cdnResponses.isEmpty(), "no CDN responses set to delay");
        cdnResponses.forEach(r -> r.withDelay(delay));
        mockServer.setCdnResponses(cdnResponses);
    }

    /** Marks the end of a scenario's arrangement: counts and tick boundaries run from here. */
    private void markArranged() {
        pollingStartedAtMs = System.currentTimeMillis();
        resetCountBaseline();
    }

    private void resetCountBaseline() {
        countBaseline = mockServer.countCdnRequests();
    }

    private int countFurtherRequests() {
        return mockServer.countCdnRequests() - countBaseline;
    }

    /** Next tick boundary on the cadence anchored at {@link #pollingStartedAtMs}. */
    private long computeNextTickAtMs() {
        long period = POLL_INTERVAL.toMillis();
        long elapsed = System.currentTimeMillis() - pollingStartedAtMs;
        return pollingStartedAtMs + (elapsed / period + 1) * period;
    }

    private Long findFirstRequestAfter(long epochMs) {
        return mockServer.fetchCdnRequests().stream()
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
    private List<Long> collectTickTimes() {
        List<Long> ticks = new ArrayList<>();
        long previous = Long.MIN_VALUE;
        for (RecordedRequest request : mockServer.fetchCdnRequests()) {
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

    private void useMockServer(boolean sseEnabled) {
        if (mockServer == null) {
            mockServer = MockServer.get();
        }
        optionsBuilder = DynatraceProviderOptions.builder()
                .configOrigin(mockServer.origin())
                .metricsOrigin(mockServer.origin())
                .metricsFlushInterval(Duration.ofHours(1)); // no periodic metrics flush during a scenario
        this.sseEnabled = sseEnabled;
        if (!sseEnabled) {
            optionsBuilder.disableSse();
        }
    }

    /**
     * Waits for the SSE stream and the catch-up fetch a successful connect pulls, so a scenario
     * that starts from a READY provider never races the stream it is about to drop or message.
     * No-op when SSE is off for the scenario, as it is throughout the CDN spec.
     */
    private void awaitSseSettled() {
        if (!sseEnabled) {
            return;
        }
        assertTrue(pollUntil(SSE_CONNECT_TIMEOUT, () -> mockServer.sseClientCount() >= 1),
                "provider did not open its SSE stream");
        // Fetch 1 comes from initialize, fetch 2 from the successful connect.
        assertTrue(pollUntil(SETTLE, () -> mockServer.countCdnRequests() >= 2),
                "SSE connected but pulled no catch-up fetch");
    }

    /**
     * Embeds a freshly constructed provider in the OpenFeature SDK under this scenario's domain.
     * {@code setProviderAndWait} runs the provider's {@code initialize} and blocks until it reaches
     * READY, throwing an {@code OpenFeatureError} if initialization fails.
     */
    private void registerProviderWithSdk() {
        provider = ProviderTestSupport.createProvider(sdkKey, optionsBuilder.build());
        api.setProviderAndWait(domain, provider);
    }

    /** Rewrites a flag configuration fixture to advertise a direct SSE {@code url} */
    private String advertisingSseUrl(String configJson, String url) {
        String resolved = url.replace(MOCK_SERVER_PLACEHOLDER,
                URI.create(mockServer.origin()).getAuthority());
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(configJson);
            ObjectNode stream = MAPPER.createObjectNode().put("type", "sse").put("url", resolved);
            root.set("eventStreams", MAPPER.createArrayNode().add(stream));
            return root.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("could not rewrite flag config to advertise " + resolved, e);
        }
    }

    /** Waits for a fetch to arrive within {@code interval} (plus slack for scheduling jitter). */
    private void awaitCdnRequestWithin(Duration interval) {
        int baseline = mockServer.countCdnRequests();
        waitFor(interval, () -> mockServer.countCdnRequests() > baseline);
    }

    /**
     * Returns the next captured event of type {@code expected}, advancing the cursor past it so
     * repeated steps (e.g. READY at startup and READY again after recovery) consume distinct events.
     */
    private EventDetails consumeEvent(ProviderEvent expected) {
        long deadline = System.currentTimeMillis() + EVENT_TIMEOUT.toMillis();
        while (true) {
            synchronized (events) {
                for (int i = eventCursor; i < events.size(); i++) {
                    RecordedEvent recorded = events.get(i);
                    if (recorded.event == expected) {
                        eventCursor = i + 1;
                        return recorded.details;
                    }
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                fail("no " + expected + " event emitted within " + EVENT_TIMEOUT.toSeconds()
                        + "s; saw " + eventNames());
                return null; // unreachable
            }
            sleep(Duration.ofMillis(100));
        }
    }

    private List<String> eventNames() {
        List<String> names = new ArrayList<>();
        synchronized (events) {
            for (int i = eventCursor; i < events.size(); i++) {
                names.add(events.get(i).event.name());
            }
        }
        return names;
    }

    /** Strips one matching pair of surrounding single or double quotes, if present. */
    private static String unquote(String value) {
        String trimmed = value.strip();
        if (trimmed.length() >= 2
                && (trimmed.startsWith("\"") && trimmed.endsWith("\"")
                || trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** A provider event captured from this scenario's client event bus. */
    private static final class RecordedEvent {
        final ProviderEvent event;
        final EventDetails details;

        RecordedEvent(ProviderEvent event, EventDetails details) {
            this.event = event;
            this.details = details;
        }
    }

    private RecordedRequest getFirstCdnRequest() {
        return getNthCdnRequest(0);
    }

    /** Returns the 0-indexed recorded CDN request, asserting at least that many were seen. */
    private RecordedRequest getNthCdnRequest(int index) {
        List<RecordedRequest> requests = mockServer.fetchCdnRequests();
        assertTrue(requests.size() > index,
                "expected at least " + (index + 1) + " CDN request(s), saw " + requests.size());
        return requests.get(index);
    }

    private boolean isFlagValue(String flag, boolean expected) {
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
    private static String loadFlagConfig(String flagFileName) {
        Path file = FLAGS_DIR.resolve(flagFileName + ".json");
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read flag config fixture: " + file, e);
        }
    }

    /** Polls {@code condition} until it holds or {@code timeout} elapses; reports whether it held. */
    private static boolean pollUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(Duration.ofMillis(100));
        }
        return condition.getAsBoolean();
    }

    /** Waits for {@code condition} to hold, failing the scenario if {@code timeout} elapses first. */
    private static void waitFor(Duration timeout, BooleanSupplier condition) {
        assertTrue(pollUntil(timeout, condition), "condition not met within " + timeout.toSeconds() + "s");
    }

    private static void assertHoldsFor(Duration duration, BooleanSupplier condition, String message) {
        assertHoldsFor(duration, condition, () -> message);
    }

    private static void assertHoldsFor(Duration duration, BooleanSupplier condition, Supplier<String> message) {
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
