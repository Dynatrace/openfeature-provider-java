package com.dynatrace.openfeature;

import dev.openfeature.contrib.tools.flagd.api.FlagStoreException;
import dev.openfeature.contrib.tools.flagd.core.FlagdCore;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
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
 * Dynatrace CDN with ETag revalidation, polling, retry, and state management.
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
    static final Duration POLL_INTERVAL = Duration.ofSeconds(10);
    static final Duration GRACE_PERIOD = Duration.ofMinutes(1);

    /** SDK key format: {token}.server_{geo}_{publicRandom}.{secret}_{sha1} */
    static final Pattern KEY_PATTERN = Pattern.compile(
            "^(dt[0-9]{2})\\.(server)_([a-z]{2,})_([a-zA-Z0-9]{10,})\\.([a-fA-F0-9]{32,})_([a-f0-9]{10,})$");

    private static final Metadata METADATA = () -> PROVIDER_NAME;

    private final String cdnUrl;
    private final FlagdCore flagdCore;
    private final CdnFetcher fetcher;
    private final Duration pollInterval;
    private final Duration gracePeriod;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private final AtomicReference<ProviderState> state = new AtomicReference<>(ProviderState.NOT_READY);
    private volatile PollingManager pollingManager;

    // grace timer; transitions STALE -> ERROR after grace period
    private volatile ScheduledExecutorService graceScheduler;
    private volatile ScheduledFuture<?> graceTimer;

    /**
     * Creates a provider from the SDK key. CDN URL is derived automatically from the key.
     *
     * @param sdkKey SDK key in the format {@code dt{NN}.server_{geo}_{rand}.{secret}_{sha}}
     * @throws IllegalArgumentException if the key is blank or malformed
     */
    public DynatraceProvider(String sdkKey) {
        this(buildCdnUrl(sdkKey, null), new FlagdCore(),
                new CdnFetcher(buildCdnUrl(sdkKey, null), buildDefaultHttpClient()),
                validatedPollInterval(POLL_INTERVAL), GRACE_PERIOD);
    }

    /**
     * Creates a provider from the SDK key with additional options.
     *
     * @param sdkKey   SDK key in the format {@code dt{NN}.server_{geo}_{rand}.{secret}_{sha}}
     * @param options  optional configuration (e.g. proxy base URL override)
     * @throws IllegalArgumentException if the key is blank or malformed
     */
    public DynatraceProvider(String sdkKey, DynatraceProviderOptions options) {
        this(buildCdnUrl(sdkKey, options), new FlagdCore(),
                new CdnFetcher(buildCdnUrl(sdkKey, options), buildDefaultHttpClient()),
                validatedPollInterval(POLL_INTERVAL), GRACE_PERIOD);
    }

    // for testing
    DynatraceProvider(String cdnUrl, FlagdCore flagdCore, CdnFetcher fetcher) {
        this(cdnUrl, flagdCore, fetcher, POLL_INTERVAL, GRACE_PERIOD);
    }

    // for testing
    DynatraceProvider(String cdnUrl, FlagdCore flagdCore, CdnFetcher fetcher,
                      Duration pollInterval, Duration gracePeriod) {
        this.cdnUrl = cdnUrl;
        this.flagdCore = flagdCore;
        this.fetcher = fetcher;
        this.pollInterval = pollInterval;
        this.gracePeriod = gracePeriod;
    }

    // poll interval must exceed the per-request timeout; otherwise the ticker cancels every
    // poll before its first attempt can complete
    private static Duration validatedPollInterval(Duration pollInterval) {
        if (pollInterval.compareTo(CdnFetcher.REQUEST_TIMEOUT) <= 0) {
            throw new IllegalArgumentException(
                    "pollInterval (" + pollInterval.toMillis() + "ms) must be greater than the "
                    + "request timeout (" + CdnFetcher.REQUEST_TIMEOUT.toMillis() + "ms)");
        }
        return pollInterval;
    }

    static String buildCdnUrl(String sdkKey, DynatraceProviderOptions options) {
        if (sdkKey == null || sdkKey.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }
        String trimmed = sdkKey.strip();
        Matcher m = KEY_PATTERN.matcher(trimmed);
        if (!m.matches()) {
            int lastDot = trimmed.lastIndexOf('.');
            String publicPart = lastDot > 0 ? trimmed.substring(0, lastDot) : "<redacted>";
            throw new IllegalArgumentException(
                    "invalid key format (public: '" + publicPart + "'); "
                    + "expected dt{NN}.server_{geo}_{rand}.{secret}_{sha}");
        }
        String type = m.group(2);
        String geo = m.group(3);
        String override = options != null ? options.getConfigOrigin() : null;
        String base = override != null
                ? override.replaceAll("/+$", "") + "/" + type + "/"
                : "https://cdn." + geo + ".fm.dynatrace.com/" + type + "/";
        return base + trimmed + ".json";
    }

    @Override
    public Metadata getMetadata() {
        return METADATA;
    }

    /**
     * Fetches the initial configuration from the CDN and starts polling.
     *
     * <p>The SDK calls this before making the provider available. It emits {@code PROVIDER_READY}
     * on success or {@code PROVIDER_ERROR} if this method throws.
     */
    @Override
    public void initialize(EvaluationContext evaluationContext) throws Exception {
        String host = java.net.URI.create(cdnUrl).getHost();
        log.info("initializing Dynatrace feature flag provider (host={})", host);

        FetchResult result;
        try {
            result = fetcher.fetch();
        } catch (IOException e) {
            throw new RuntimeException("initial CDN fetch failed: " + e.getMessage(), e);
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
        } catch (FlagStoreException e) {
            throw new RuntimeException("initial CDN config is invalid: " + e.getMessage(), e);
        }

        state.set(ProviderState.READY);

        // start grace timer scheduler (lazy; thread only created when needed)
        graceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dt-provider-grace-timer");
            t.setDaemon(true);
            return t;
        });

        // start polling
        pollingManager = new PollingManager(
                fetcher::fetch,
                this::storeFlags,
                this::onConfigurationChanged,
                this::onFetchSuccess,
                this::onFetchFailure,
                pollInterval);
        pollingManager.start();

        log.info("Dynatrace provider initialized successfully");
    }

    /** Cancels in-flight requests, stops polling, and cleans up. Idempotent. */
    @Override
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        log.info("shutting down Dynatrace provider");
        PollingManager pm = pollingManager;
        if (pm != null) {
            pm.stop();
        }
        cancelGraceTimer();
        ScheduledExecutorService gs = graceScheduler;
        if (gs != null) {
            gs.shutdown();
        }
        state.set(ProviderState.NOT_READY);
        super.shutdown();
        log.info("Dynatrace provider shut down");
    }


    private List<String> storeFlags(String json) {
        try {
            return flagdCore.setFlagsAndGetChangedKeys(json);
        } catch (FlagStoreException e) {
            throw new RuntimeException("failed to store flag configuration: " + e.getMessage(), e);
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
            log.warn("poll failed, transitioning to STALE: {}", reason);
            emitProviderStale(ProviderEventDetails.builder()
                    .message(reason)
                    .build());
            startGraceTimer();
        } else {
            log.warn("poll failed (state={}): {}", state.get(), reason);
        }
    }

    private void onConfigurationChanged(List<String> changedKeys) {
        emitProviderConfigurationChanged(ProviderEventDetails.builder()
                .flagsChanged(changedKeys)
                .build());
    }

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

    private static HttpClient buildDefaultHttpClient() {
        return HttpClient.newBuilder()
                .build();
    }
}
