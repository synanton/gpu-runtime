package org.synanton.gpu.domain.model;

import org.synanton.gpu.v1.Operation;

/**
 * The outcome of routing a logical model to a concrete provider runtime (PR #15
 * review §5: one authoritative routing model — no provider-enum special cases).
 *
 * <p>Produced by {@code ProviderRouter}, consumed by the runtime registry.
 * Carries everything the runtime needs to dispatch; Kubernetes topology never
 * appears here.
 *
 * @param logicalModelId  the Synanton logical model ID from the request (public contract)
 * @param providerId      the configured provider key (e.g. {@code openai}, {@code mock})
 * @param providerModelId the upstream model ID sent to the provider (never exposed downstream)
 * @param operation       SYNTHESIZE / EMBED / RERANK
 * @param endpoint        provider base URL
 * @param apiKey          provider credentials — redacted from {@link #toString()}, never logged
 * @param routingMode     {@code external} for provider dispatch; {@code local} for GPU-5 vLLM
 */
public record RoutingDecision(
        String logicalModelId,
        String providerId,
        String providerModelId,
        Operation operation,
        String endpoint,
        String apiKey,
        String routingMode) {

    public static final String MODE_EXTERNAL = "external";
    public static final String MODE_LOCAL = "local";

    /** Provider id used for the local (GPU-5 vLLM) path. */
    public static final String LOCAL_PROVIDER = "local";

    public boolean isLocal() {
        return MODE_LOCAL.equals(routingMode);
    }

    public static RoutingDecision local(String logicalModelId, Operation operation, String vllmEndpoint) {
        return new RoutingDecision(
                logicalModelId, LOCAL_PROVIDER, logicalModelId, operation,
                vllmEndpoint, null, MODE_LOCAL);
    }

    @Override
    public String toString() {
        return "RoutingDecision{logicalModelId=" + logicalModelId
                + ", providerId=" + providerId
                + ", providerModelId=" + providerModelId
                + ", operation=" + operation
                + ", endpoint=" + endpoint
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<none>" : "<redacted>")
                + ", routingMode=" + routingMode + "}";
    }
}
