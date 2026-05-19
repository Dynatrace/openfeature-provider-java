package com.dynatrace.openfeature;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Builds the {@code EvaluationBatch} JSON payload posted to the Dynatrace evaluation-metrics
 * endpoint. Pulled out of {@link EvaluationMetricsFlusher} so the wire format lives next to its
 * own tests and the flusher stays focused on scheduling and delivery.
 *
 * <p>Field ordering matches the documented payload shape; {@link ObjectNode} preserves insertion
 * order on serialization, so callers don't need to do anything special.
 */
final class EvaluationMetricsPayload {

    static final String SDK_TYPE = "server";

    // ObjectMapper is documented thread-safe once configured, so a single shared instance is fine.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvaluationMetricsPayload() {
    }

    /**
     * Serialize a drained metrics batch into the documented JSON shape.
     *
     * <p>{@code version} must be non-null; callers are responsible for ensuring a config version
     * is available before invoking this method (see the null-guard in
     * {@code EvaluationMetricsFlusher#flushBatch}).
     */
    static String build(
            Map<String, ? extends Map<EvaluationMetricKey, EvaluationMetricAggregate>> snapshot,
            String version,
            String sdkVersion,
            String providerName) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", version);
        root.put("sdkType", SDK_TYPE);
        root.put("sdkVersion", sdkVersion);
        root.put("providerName", providerName);

        ArrayNode evaluations = root.putArray("evaluations");
        for (Map.Entry<String, ? extends Map<EvaluationMetricKey, EvaluationMetricAggregate>> outer
                : snapshot.entrySet()) {
            String flagKey = outer.getKey();
            for (EvaluationMetricAggregate aggregate : outer.getValue().values()) {
                ObjectNode eval = evaluations.addObject();
                eval.put("flagKey", flagKey);
                eval.put("variant", aggregate.getVariant());
                eval.put("evaluationReason", aggregate.getReason());
                if (aggregate.getErrorCode() != null) {
                    eval.put("errorCode", aggregate.getErrorCode());
                }
                eval.put("timestampSec", aggregate.getTimestampSec());
                eval.put("count", aggregate.getCount());
            }
        }

        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // ObjectNode-tree serialization shouldn't throw in practice (no custom serializers,
            // no I/O) — but surface clearly if Jackson ever changes that.
            throw new IllegalStateException("failed to serialize evaluation metrics payload", e);
        }
    }
}
