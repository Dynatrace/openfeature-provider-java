package com.dynatrace.openfeature;

import com.dynatrace.openfeature.http.CdnFetcher;
import com.dynatrace.openfeature.http.FetchResult;
import dev.openfeature.contrib.tools.flagd.api.FlagStoreException;
import dev.openfeature.contrib.tools.flagd.core.FlagdCore;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dynatrace OpenFeature provider for Java (server-side).
 *
 * <p>Wraps flagd-core for in-process evaluation and adds HTTP config fetching from the
 * Dynatrace CDN with ETag revalidation.
 *
 * <p>Usage:
 * <pre>{@code
 * DynatraceProviderOptions options = DynatraceProviderOptions.builder("dt_server_us_de848e97a9cc4cc78aae568e65f49a9d")
 *     .build();
 *
 * OpenFeatureAPI.getInstance().setProvider(new DynatraceProvider(options));
 * }</pre>
 */
public final class DynatraceProvider extends EventProvider {

    private static final Logger log = LoggerFactory.getLogger(DynatraceProvider.class);

    static final String PROVIDER_NAME = "dynatrace";

    private static final Metadata METADATA = () -> PROVIDER_NAME;

    private final DynatraceProviderOptions options;
    private final FlagdCore flagdCore;
    private final CdnFetcher fetcher;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public DynatraceProvider(DynatraceProviderOptions options) {
        this(options, new FlagdCore(), buildDefaultHttpClient());
    }

    // visible for testing
    DynatraceProvider(DynatraceProviderOptions options, FlagdCore flagdCore, HttpClient httpClient) {
        this.options = options;
        this.flagdCore = flagdCore;
        this.fetcher = new CdnFetcher(options.getCdnUrl(), httpClient);
    }

    @Override
    public Metadata getMetadata() {
        return METADATA;
    }

    /**
     * Fetches the initial configuration from the CDN.
     *
     * <p>The SDK calls this before making the provider available. It emits {@code PROVIDER_READY}
     * on success or {@code PROVIDER_ERROR} if this method throws.
     */
    @Override
    public void initialize(EvaluationContext evaluationContext) throws Exception {
        log.info("initializing Dynatrace provider (url={})", options.getCdnUrl());

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

        log.info("Dynatrace provider initialized successfully");
    }

    /** Cancels in-flight requests and cleans up. Idempotent. */
    @Override
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        log.info("shutting down Dynatrace provider");
        super.shutdown();
        log.info("Dynatrace provider shut down");
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

    private static HttpClient buildDefaultHttpClient() {
        return HttpClient.newBuilder()
                .build();
    }
}
