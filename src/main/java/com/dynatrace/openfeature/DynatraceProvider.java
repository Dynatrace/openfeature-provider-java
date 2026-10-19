package com.dynatrace.openfeature;

import dev.openfeature.contrib.tools.flagd.api.FlagStoreException;
import dev.openfeature.contrib.tools.flagd.core.FlagdCore;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynatrace OpenFeature provider for Java (server-side).
 *
 * <p>Wraps flagd-core for in-process evaluation and adds HTTP config fetching from the
 * Dynatrace CDN with ETag revalidation, SSE change notifications, polling, retry, and state
 * management. Records evaluation metrics in-process and periodically POSTs them to the Dynatrace
 * metrics endpoint.
 *
 * <p>When SSE is connected, polling acts as a 10-minute safety net. When SSE is
 * disconnected or unavailable, polling runs every 10 seconds for faster recovery.
 *
 * <p>Usage:
 * <pre>{@code
 * // minimal
 * OpenFeatureAPI.getInstance().setProvider(new DynatraceProvider("dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5"));
 *
 * // with proxy override
 * OpenFeatureAPI.getInstance().setProvider(
 *     new DynatraceProvider("dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5",
 *         DynatraceProviderOptions.builder().configOrigin("https://my-proxy.example.com").build()));
 * }</pre>
 */
public final class DynatraceProvider extends EventProvider {

    private static final Logger log = LoggerFactory.getLogger(DynatraceProvider.class);

    static final String PROVIDER_NAME = "dynatrace";

    /** Poll interval when SSE is connected: SSE delivers notifications, polling is a safety net. */
    static final Duration POLL_INTERVAL_SSE_CONNECTED = Duration.ofMinutes(10);

    /** Poll interval when SSE is disconnected or disabled: aggressive, to catch missed notifications. */
    static final Duration POLL_INTERVAL_SSE_DISCONNECTED = Duration.ofSeconds(10);

    static final Duration GRACE_PERIOD = Duration.ofMinutes(1);

    /** SDK key format: {token}.server_{geo}_{publicRandom}.{secret}_{sha1} */
    static final Pattern KEY_PATTERN = Pattern.compile(
            "^(dt[0-9]{2})\\.(server)_([a-z]{2,})_([a-zA-Z0-9]{10,})\\.([a-fA-F0-9]{32,})_([a-f0-9]{10,})$");

    private static final Metadata METADATA = () -> PROVIDER_NAME;

    private final String cdnUrl;
    private final boolean sseEnabled;
    private final FlagdCore flagdCore;
    private final CdnFetcher fetcher;
    private final Duration gracePeriod;
    private final Duration pollIntervalConnected;
    private final Duration pollIntervalDisconnected;
    private final DynatraceMetricsAggregator metricsAggregator;
    private final EvaluationMetricsFlusher metricsFlusher;

    // Hook is used as a raw type to match OpenFeature's getProviderHooks() contract:
    //   public List<Hook> getProviderHooks()  // raw on purpose; hooks have heterogeneous T
    @SuppressWarnings("rawtypes")
    private final List<Hook> providerHooks;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private final AtomicReference<ProviderState> state = new AtomicReference<>(ProviderState.NOT_READY);

    /** True while the SSE stream is open; only selects the poll cadence. */
    private final AtomicBoolean sseConnected = new AtomicBoolean(false);

    private volatile SseManager sseManager;

    /** True while an SSE connection is being opened; reserves the slot without publishing it. */
    private boolean sseStarting;
    private volatile PollingManager pollingManager;

    // grace timer: transitions STALE -> ERROR after grace period
    private volatile ScheduledExecutorService graceScheduler;
    private volatile ScheduledFuture<?> graceTimer;

    /**
     * Creates a provider from the SDK key. CDN URL is derived automatically from the key.
     *
     * @param sdkKey SDK key in the format {@code dt{NN}.server_{geo}_{rand}.{secret}_{sha}}
     * @throws IllegalArgumentException if the key is blank or malformed
     */
    public DynatraceProvider(String sdkKey) {
        this(sdkKey, null);
    }

    /**
     * Creates a provider from the SDK key with additional options.
     *
     * @param sdkKey   SDK key in the format {@code dt{NN}.server_{geo}_{rand}.{secret}_{sha}}
     * @param options  optional configuration (e.g. proxy base URL override)
     * @throws IllegalArgumentException if the key is blank or malformed
     */
    public DynatraceProvider(String sdkKey, DynatraceProviderOptions options) {
        this(sdkKey, options, new FlagdCore(), buildDefaultHttpClient());
    }

    // for testing
    DynatraceProvider(String sdkKey, String cdnUrl, FlagdCore flagdCore, CdnFetcher fetcher) {
        this(sdkKey, null, cdnUrl, flagdCore, fetcher, buildDefaultHttpClient(),
                POLL_INTERVAL_SSE_CONNECTED, POLL_INTERVAL_SSE_DISCONNECTED, GRACE_PERIOD);
    }

    // for testing: a single fixed poll interval for both SSE states (cadence independent of SSE)
    DynatraceProvider(String sdkKey, String cdnUrl, FlagdCore flagdCore, CdnFetcher fetcher,
                      Duration pollInterval, Duration gracePeriod) {
        this(sdkKey, null, cdnUrl, flagdCore, fetcher, buildDefaultHttpClient(),
                pollInterval, pollInterval, gracePeriod);
    }

    // tests only: lets tests inject every collaborator individually
    DynatraceProvider(String sdkKey, DynatraceProviderOptions options, FlagdCore flagdCore, HttpClient httpClient) {
        this(sdkKey, options, buildCdnUrl(sdkKey, options), flagdCore,
                new CdnFetcher(buildCdnUrl(sdkKey, options), httpClient), httpClient,
                POLL_INTERVAL_SSE_CONNECTED, POLL_INTERVAL_SSE_DISCONNECTED, GRACE_PERIOD);
    }

    // tests only: production wiring with an overridden SSE-connected poll interval and grace period
    DynatraceProvider(String sdkKey, DynatraceProviderOptions options,
                      Duration pollIntervalConnected, Duration gracePeriod) {
        this(sdkKey, options, buildCdnUrl(sdkKey, options), new FlagdCore(), buildDefaultHttpClient(),
                pollIntervalConnected, gracePeriod);
    }

    private DynatraceProvider(String sdkKey, DynatraceProviderOptions options, String cdnUrl, FlagdCore flagdCore,
                              HttpClient httpClient, Duration pollIntervalConnected, Duration gracePeriod) {
        this(sdkKey, options, cdnUrl, flagdCore, new CdnFetcher(cdnUrl, httpClient), httpClient,
                pollIntervalConnected, POLL_INTERVAL_SSE_DISCONNECTED, gracePeriod);
    }

    // tests only: as above, plus a pre-built CdnFetcher and timing controls (fixed cadence both states)
    DynatraceProvider(String sdkKey, DynatraceProviderOptions options, FlagdCore flagdCore, CdnFetcher fetcher,
                      Duration pollInterval, Duration gracePeriod) {
        this(sdkKey, options, buildCdnUrl(sdkKey, options), flagdCore, fetcher, buildDefaultHttpClient(),
                pollInterval, pollInterval, gracePeriod);
    }

    // for testing: distinct connected/disconnected cadences, to observe the SSE-transition consequence
    DynatraceProvider(String sdkKey, String cdnUrl, FlagdCore flagdCore, CdnFetcher fetcher,
                      Duration pollIntervalConnected, Duration pollIntervalDisconnected,
                      Duration gracePeriod) {
        this(sdkKey, null, cdnUrl, flagdCore, fetcher, buildDefaultHttpClient(),
                pollIntervalConnected, pollIntervalDisconnected, gracePeriod);
    }

    private DynatraceProvider(String sdkKey, DynatraceProviderOptions options, String cdnUrl,
                              FlagdCore flagdCore, CdnFetcher fetcher, HttpClient metricsHttpClient,
                              Duration pollIntervalConnected, Duration pollIntervalDisconnected,
                              Duration gracePeriod) {
        String trimmedSdkKey = validateSdkKey(sdkKey).group(0);
        DynatraceProviderOptions safeOptions = options == null ? DynatraceProviderOptions.builder().build() : options;
        this.cdnUrl = cdnUrl;
        this.sseEnabled = safeOptions.isSseEnabled();
        this.flagdCore = flagdCore;
        this.fetcher = fetcher;
        this.pollIntervalConnected = pollIntervalConnected;
        this.pollIntervalDisconnected = pollIntervalDisconnected;
        this.gracePeriod = gracePeriod;
        this.metricsAggregator = new DynatraceMetricsAggregator(safeOptions.getMetricsHardCapDistinctFlags());
        this.metricsFlusher = new EvaluationMetricsFlusher(
                metricsAggregator,
                metricsHttpClient,
                trimmedSdkKey,
                buildMetricsUrl(trimmedSdkKey, safeOptions),
                safeOptions.getMetricsFlushInterval(),
                safeOptions.getMetricsSoftCapDistinctFlags(),
                resolveSdkVersion(),
                PROVIDER_NAME);
        this.providerHooks = Collections.singletonList(
                new DynatraceMetricsHook(metricsAggregator, Clock.systemUTC(), metricsFlusher::onRecord));
    }

    static String buildCdnUrl(String sdkKey, DynatraceProviderOptions options) {
        Matcher m = validateSdkKey(sdkKey);
        String trimmed = m.group(0);
        String type = m.group(2);
        String geo = m.group(3);
        URI override = options != null ? options.getConfigOrigin() : null;
        String base = override != null
                ? override.getScheme() + "://" + override.getAuthority() + "/" + type + "/"
                : "https://cdn." + geo + ".fm.dynatrace.com/" + type + "/";
        return base + trimmed + ".json";
    }

    static String buildMetricsUrl(String sdkKey, DynatraceProviderOptions options) {
        if (options != null && options.getMetricsUrl() != null) {
            return options.getMetricsUrl();
        }
        Matcher m = validateSdkKey(sdkKey);
        String geo = m.group(3);
        return "https://metrics." + geo + ".fm.dynatrace.com/v1/metrics";
    }

    private static Matcher validateSdkKey(String sdkKey) {
        if (sdkKey == null || sdkKey.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }
        String trimmed = sdkKey.strip();
        Matcher m = KEY_PATTERN.matcher(trimmed);
        if (!m.matches()) {
            int lastDot = trimmed.lastIndexOf('.');
            String publicPart = lastDot > 0 ? trimmed.substring(0, lastDot) : "<redacted>";
            throw new IllegalArgumentException(
                    "invalid key (public: '" + publicPart + "'); "
                    + "expected dt{NN}.server_{geo}_{rand}.{secret}_{sha}");
        }
        return m;
    }

    @Override
    public Metadata getMetadata() {
        return METADATA;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<Hook> getProviderHooks() {
        return providerHooks;
    }

    /**
     * Fetches the initial configuration from the CDN, opens the SSE connection, starts polling,
     * and starts the evaluation-metrics flusher.
     *
     * <p>Initialization order (per spec 3.1):
     * <ol>
     *   <li>Fetch flagd config JSON from CDN</li>
     *   <li>Load config into flagd-core</li>
     *   <li>Open SSE connection (async; errors handled by callbacks)</li>
     *   <li>Start polling timer, only once step 1 has completed, so the initial fetch never
     *       contends with the first tick (spec 2.1.1)</li>
     *   <li>Emit {@code PROVIDER_READY} (via return)</li>
     * </ol>
     *
     * <p>A failed initial fetch still starts the polling timer before rethrowing, so it recovers
     * like any other failure (spec 2.4); otherwise a transient 5xx at startup would strand the
     * provider in {@code ERROR} with nothing scheduled to retry. There is no config to read an
     * SSE URL from in that case, so it polls at the disconnected cadence until a poll succeeds
     * and SSE starts from the config that poll returned.
     */
    @Override
    public void initialize(EvaluationContext evaluationContext) throws Exception {
        String host = URI.create(cdnUrl).getHost();
        log.info("initializing Dynatrace feature flag provider (host={})", host);
        if (!sseEnabled) {
            log.debug("SSE disabled by configuration; running in polling-only mode");
        }

        // Created before the fetch: the failure path needs it for the grace timer.
        graceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dt-provider-grace-timer");
            t.setDaemon(true);
            return t;
        });

        RuntimeException initFailure = null;
        try {
            FetchResult result;
            try {
                result = fetcher.fetch();
            } catch (IOException e) {
                throw new RuntimeException("initial CDN fetch failed: " + e.getMessage(), e);
            }

            if (result.isSkipped()) {
                throw new RuntimeException("initial CDN fetch was skipped; no request was made");
            }
            if (!result.isSuccess()) {
                throw new RuntimeException("initial CDN fetch returned HTTP " + result.getStatusCode());
            }

            String body = result.getBody();
            if (body == null || body.isBlank()) {
                throw new RuntimeException("initial CDN response body was empty");
            }

            try {
                flagdCore.setFlags(body);
                updateMetricsVersionFromFlagSet();
            } catch (FlagStoreException e) {
                throw new RuntimeException("initial CDN config is invalid: " + e.getMessage(), e);
            }

            state.set(ProviderState.READY);

            // open SSE connection (spec 3.1 step 3)
            ensureSseStarted(body);
        } catch (RuntimeException e) {
            // Don't rethrow yet: polling below recovers, and invalidating stops a phantom 304
            // from "recovering" to READY with no flags loaded.
            initFailure = e;
            fetcher.invalidateCache();
            state.set(ProviderState.ERROR);
            log.error("initialization failed; starting polling-only recovery at {}: {}",
                    pollIntervalDisconnected, e.getMessage());
        }

        // Started only now, after the initial fetch has completed, so init never contends with
        // the first tick (spec 2.1.1). Reached on the failure path too.
        pollingManager = new PollingManager(
                fetcher::fetch,
                this::storeFlags,
                this::onConfigurationChanged,
                this::onFetchSuccess,
                this::onFetchFailure,
                pollIntervalDisconnected);
        pollingManager.start();
        // If SSE connected during init (before the manager existed), adopt the connected cadence.
        if (sseConnected.get()) {
            pollingManager.setInterval(pollIntervalConnected);
        }

        metricsFlusher.start();

        if (initFailure != null) {
            throw initFailure;
        }
        log.info("Dynatrace provider initialized successfully");
    }

    /**
     * Opens the SSE connection using the SSE URL carried in the CDN config, if one is present and
     * SSE is enabled. Idempotent, so the post-failed-init recovery path can call it on the first
     * successful poll without risking a second stream.
     */
    private void ensureSseStarted(String body) {
        SseManager sm;
        synchronized (this) {
            if (!sseEnabled || sseManager != null || sseStarting || shutdown.get()) {
                return;
            }
            URI cdnUri = URI.create(cdnUrl);
            String cdnOrigin = cdnUri.getScheme() + "://" + cdnUri.getAuthority();
            String sseUrl = extractSseUrl(body, cdnOrigin);
            if (sseUrl == null) {
                // The server always sends eventStreams, so this is not worth logging.
                return;
            }
            sm = new SseManager(sseUrl, this::onSseConnected, this::onSseDisconnected, this::onSseMessage);
            // Reserve the slot; start() runs outside the monitor so it cannot block shutdown.
            sseStarting = true;
        }

        boolean started = false;
        try {
            sm.start();
            started = true;
        } catch (Exception e) {
            log.warn("failed to start SSE connection; falling back to polling-only mode: {}", e.getMessage());
        } finally {
            // finally: an Error out of start() must not leave the slot reserved forever.
            if (!publishSseManager(sm, started) && started) {
                // shutdown() read sseManager before we could publish, so nothing else stops this.
                log.debug("provider shut down while SSE was connecting; closing the new stream");
                sm.stop();
            }
        }
    }

    /**
     * Releases the reservation taken by {@link #ensureSseStarted} and publishes {@code sm} so
     * {@link #shutdown()} can find it.
     *
     * @return true if {@code sm} was published, false if the caller still owns it
     */
    private synchronized boolean publishSseManager(SseManager sm, boolean started) {
        sseStarting = false;
        if (started && !shutdown.get()) {
            sseManager = sm;
            return true;
        }
        return false;
    }

    /**
     * Closes SSE, stops polling, cancels in-flight requests, performs a final metrics flush, and
     * cleans up. Idempotent.
     */
    @Override
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        log.info("shutting down Dynatrace provider");

        // spec 6.1: close SSE first. The monitor makes this atomic with the publish in
        // ensureSseStarted, so exactly one of the two stops the stream.
        SseManager sm;
        synchronized (this) {
            sm = sseManager;
        }
        if (sm != null) {
            sm.stop();
        }
        PollingManager pm = pollingManager;
        if (pm != null) {
            pm.stop();
        }
        cancelGraceTimer();
        ScheduledExecutorService gs = graceScheduler;
        if (gs != null) {
            gs.shutdown();
        }
        try {
            metricsFlusher.shutdown();
        } catch (RuntimeException e) {
            log.warn("metrics flusher shutdown failed: {}", e.toString());
        }
        state.set(ProviderState.NOT_READY);
        super.shutdown();
        log.info("Dynatrace provider shut down");
    }

    // SSE only selects the poll cadence; provider state follows CDN fetch outcomes alone
    synchronized void onSseConnected() {
        sseConnected.set(true);
        PollingManager pm = pollingManager;
        if (pm == null) {
            return;
        }
        log.info("SSE connected; poll interval switched to {}", pollIntervalConnected);
        pm.setInterval(pollIntervalConnected);
        // catch up on anything announced while the stream was down; never dropped
        pm.requestOutOfBandFetch(null);
    }

    synchronized void onSseDisconnected() {
        if (!sseConnected.getAndSet(false)) {
            return;
        }
        log.warn("SSE disconnected; poll interval switched to {}", pollIntervalDisconnected);
        PollingManager pm = pollingManager;
        if (pm != null) {
            pm.setInterval(pollIntervalDisconnected);
        }
    }

    /** The poll/fetch coordinator, or null before {@link #initialize} has run. */
    PollingManager pollingManager() {
        return pollingManager;
    }

    /**
     * Handles a parsed SSE message per the SSE message format spec:
     * <ul>
     *   <li>Ignores any type other than {@code refetchConfig} (this is a server-side provider).</li>
     *   <li>Otherwise triggers a conditional GET against the CDN URL.</li>
     * </ul>
     *
     * <p>The message is only a "something changed" signal; the fetch always pulls CDN-current
     * config, and {@link CdnFetcher} rejects any response whose {@code Last-Modified} isn't newer
     * than what we last applied (it returns 304). Duplicate, stale, or out-of-order pokes
     * therefore collapse to a cheap conditional GET.
     *
     * <p>The announced {@code lastModified} is forwarded only so a re-fetch deferred behind an
     * in-flight fetch can be dropped when that fetch already returned a config at least as new
     * (KB section 2.1.1). It never drops the notification up front.
     */
    void onSseMessage(SseMessage msg) {
        if (msg == null) {
            return;
        }
        if (!SseMessage.TYPE_REFETCH_CONFIG.equals(msg.type)) {
            log.debug("ignoring SSE message of type '{}' (server provider only handles '{}')",
                    msg.type, SseMessage.TYPE_REFETCH_CONFIG);
            return;
        }
        log.debug("SSE refetchConfig received; triggering CDN re-fetch (lastModified={})",
                msg.lastModified);
        PollingManager pm = pollingManager;
        if (pm != null) {
            pm.requestOutOfBandFetch(msg.lastModified);
        }
    }

    // -- poll callbacks --

    // package-private to let tests drive a config switch without standing up a real PollingManager
    List<String> storeFlags(String json) {
        if (shutdown.get()) {
            throw new IllegalStateException(
                    "storeFlags called after provider shutdown; ignoring new config");
        }
        DynatraceMetricsAggregator.DrainedBatch sealedMetrics = metricsAggregator.drain();
        metricsFlusher.enqueueSealedBatch(sealedMetrics);
        metricsFlusher.triggerFlush();
        try {
            List<String> changed = flagdCore.setFlagsAndGetChangedKeys(json);
            updateMetricsVersionFromFlagSet();
            // Covers the failed-init case, where there was no config to read an SSE URL from.
            ensureSseStarted(json);
            return changed;
        } catch (FlagStoreException e) {
            // This body was rejected, so the validators cached for it must not stand in for it.
            fetcher.invalidateCache();
            throw new RuntimeException("failed to store flag configuration: " + e.getMessage(), e);
        }
    }

    private void updateMetricsVersionFromFlagSet() {
        Object version = flagdCore.getFlagSetMetadata().get("version");
        if (version instanceof String) {
            metricsAggregator.setVersion((String) version);
        }
    }

    private synchronized void onFetchSuccess() {
        cancelGraceTimer();
        ProviderState current = state.get();
        if (current == ProviderState.STALE || current == ProviderState.ERROR) {
            if (state.compareAndSet(current, ProviderState.READY)) {
                log.info("recovered from {} to READY", current);
                emitProviderReady(ProviderEventDetails.builder()
                        .message("recovered")
                        .build());
            }
        }
    }

    private synchronized void onFetchFailure(String reason) {
        if (state.compareAndSet(ProviderState.READY, ProviderState.STALE)) {
            log.warn("fetch failed, transitioning to STALE: {}", reason);
            emitProviderStale(ProviderEventDetails.builder()
                    .message(reason)
                    .build());
            startGraceTimer();
        } else {
            log.warn("fetch failed (state={}): {}", state.get(), reason);
        }
    }

    private void onConfigurationChanged(List<String> changedKeys) {
        emitProviderConfigurationChanged(ProviderEventDetails.builder()
                .flagsChanged(changedKeys)
                .build());
    }

    // -- grace timer --

    private synchronized void startGraceTimer() {
        cancelGraceTimer();
        try {
            graceTimer = graceScheduler.schedule(
                    this::graceExpired,
                    gracePeriod.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // scheduler already shut down; ignore
        }
    }

    private synchronized void cancelGraceTimer() {
        ScheduledFuture<?> timer = graceTimer;
        if (timer != null) {
            timer.cancel(false);
            graceTimer = null;
        }
    }

    private synchronized void graceExpired() {
        if (state.compareAndSet(ProviderState.STALE, ProviderState.ERROR)) {
            log.error("grace period expired, transitioning to ERROR");
            emitProviderError(ProviderEventDetails.builder()
                    .message("grace period expired; CDN unreachable")
                    .build());
        }
    }

    // -- flag evaluation (delegate to flagd-core) --

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
        return flagdCore.resolveBooleanValue(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
        return flagdCore.resolveStringValue(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
        return flagdCore.resolveIntegerValue(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
        return flagdCore.resolveDoubleValue(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
        return flagdCore.resolveObjectValue(key, defaultValue, ctx);
    }

    @Override
    public ProviderState getState() {
        return state.get();
    }

    // -- utilities --

    /**
     * Extracts the SSE URL from the CDN config JSON, using {@code fallbackOrigin} as the base
     * URL origin when the stream entry uses a structured {@code endpoint} with no explicit origin.
     *
     * <p>Looks for the first entry in {@code eventStreams} with {@code type == "sse"}.
     * Returns null if the field is absent or no SSE entry is present.
     */
    static String extractSseUrl(String configJson, String fallbackOrigin) {
        CdnConfigJSON config = CdnConfigJSON.parse(configJson);
        return config != null ? config.sseUrl(fallbackOrigin) : null;
    }

    private static HttpClient buildDefaultHttpClient() {
        return HttpClient.newBuilder().build();
    }

    private static String resolveSdkVersion() {
        Package pkg = DynatraceProvider.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version != null && !version.isBlank() ? version : "0.0.0";
    }
}
