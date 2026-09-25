package org.synanton.gpu.domain.service;

import org.synanton.gpu.adapter.out.runtime.ModelCatalogService;
import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.model.RoutingDecision;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.Operation;
import org.synanton.gpu.v1.Provider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Authoritative routing authority (PR #15 review P0.4/§5).
 *
 * <p>Replaces the old implicit model ({@code if provider == OPENAI else vLLM}),
 * which violated GPU-7's no-local-fallback security invariant. One decision point:
 *
 * <pre>
 * Logical Model ──▶ RoutingDecision ──▶ ProviderRuntime (via ProviderRuntimeRegistry)
 * </pre>
 *
 * <p>Strategies ({@code gpu-gateway.dispatch.strategy}, fail closed at startup):
 * <ul>
 *   <li>{@code stub} / {@code direct} — local GPU-5 path; a request explicitly marked
 *       {@code OPENAI} still routes externally (mixed GPU-5+GPU-7 gateway).</li>
 *   <li>{@code external} — GPU-7: provider registry only. {@code LOCAL} requests are
 *       denied; unknown/disabled providers are denied; the kill switch
 *       ({@code gpu-gateway.routing.external-enabled: false}) denies everything.
 *       There is <b>no local fallback</b>.</li>
 * </ul>
 *
 * <p>The model catalog (not the request) is authoritative for provider selection:
 * the wire {@link Provider} enum cannot express configured providers like {@code mock}.
 */
@Component
@Slf4j
public class ProviderRouter {

    /** Strategies this build supports; anything else fails startup (§5.5). */
    private static final Set<String> SUPPORTED_STRATEGIES = Set.of("stub", "direct", "external");

    private final String strategy;
    private final String vllmEndpoint;
    private final GpuGatewayProperties properties;
    private final ModelCatalogService modelCatalogService;

    public ProviderRouter(GpuGatewayProperties properties, ModelCatalogService modelCatalogService) {
        this.properties = properties;
        this.modelCatalogService = modelCatalogService;
        this.strategy = properties.getDispatch().getStrategy() == null
                ? "stub" : properties.getDispatch().getStrategy().trim();
        this.vllmEndpoint = properties.getDispatch().getVllmEndpoint();

        if (!SUPPORTED_STRATEGIES.contains(strategy)) {
            throw new IllegalStateException(
                    "Unsupported gpu-gateway.dispatch.strategy='" + strategy + "' (supported: "
                            + SUPPORTED_STRATEGIES + ") — failing closed per spec §5.5");
        }
        if ("external".equals(strategy) && properties.getProviders().isEmpty()) {
            throw new IllegalStateException(
                    "dispatch.strategy=external but gpu-gateway.providers is empty — "
                            + "no route could ever succeed; failing closed per spec §5.5");
        }
        validateAllowedModelPatterns(properties);
        log.info("ProviderRouter: strategy={} providers={}", strategy,
                properties.getProviders().keySet());
    }

    /** Fail closed (§5.5) if a catalog model violates its provider's allowed-model-pattern. */
    private static void validateAllowedModelPatterns(GpuGatewayProperties properties) {
        properties.getModelCatalog().getOperations().forEach((op, models) ->
                models.getModels().forEach((logicalId, info) -> {
                    String providerId = info.getProvider() == null ? ""
                            : info.getProvider().toLowerCase(java.util.Locale.ROOT);
                    GpuGatewayProperties.ProviderConfig provider = properties.getProviders().get(providerId);
                    if (provider == null || provider.getAllowedModelPattern() == null
                            || provider.getAllowedModelPattern().isBlank()) {
                        return;
                    }
                    String providerModelId = info.getProviderModelId() == null ? logicalId : info.getProviderModelId();
                    if (!providerModelId.matches(provider.getAllowedModelPattern())) {
                        throw new IllegalStateException("catalog model '" + logicalId + "' maps to '"
                                + providerModelId + "', which violates providers." + providerId
                                + ".allowed-model-pattern — failing closed per spec §5.5");
                    }
                }));
    }

    public boolean isExternal() {
        return "external".equals(strategy);
    }

    /**
     * Resolve the routing decision for a request, or throw {@link RoutingDeniedException}.
     * Runs before admission: a denied request is never persisted or dispatched.
     */
    public RoutingDecision route(ExecutionRequest request) {
        String logicalModelId = request.getModel();
        Operation operation = request.getOperation();

        if (!isExternal()) {
            // GPU-5 local path; explicit OPENAI requests still dispatch externally
            // (mixed-mode gateway behavior predating this router, preserved).
            if (request.getProvider() == Provider.OPENAI) {
                return externalDecision(logicalModelId, operation);
            }
            return RoutingDecision.local(logicalModelId, operation, vllmEndpoint);
        }

        // ── strategy=external (GPU-7) — fail-closed rules, in security order ──
        if (request.getProvider() == Provider.LOCAL) {
            throw new RoutingDeniedException("no_local_fallback",
                    "external-only mode: local routing is denied (spec §39)");
        }
        if (!properties.getRouting().isExternalEnabled()) {
            throw new RoutingDeniedException("routing_disabled",
                    "external routing kill switch is OFF (gpu-gateway.routing.external-enabled)");
        }
        return externalDecision(logicalModelId, operation);
    }

    /** Catalog-driven external decision; shared by external mode and mixed-mode OPENAI requests. */
    private RoutingDecision externalDecision(String logicalModelId, Operation operation) {
        // Catalog stores wire-style provider names ("MOCK", "OPENAI"); the registry
        // keys are lowercase config ids. Normalize at the boundary.
        String providerId = resolveProviderIdChecked(logicalModelId, operation)
                .toLowerCase(java.util.Locale.ROOT);

        if (RoutingDecision.LOCAL_PROVIDER.equals(providerId)) {
            // A catalog-LOCAL model can never be served by an external route (invariant 1).
            throw new RoutingDeniedException("no_local_fallback",
                    "model '" + logicalModelId + "' is LOCAL; external routing never falls back to local");
        }
        GpuGatewayProperties.ProviderConfig provider = properties.getProviders().get(providerId);
        if (provider == null) {
            throw new RoutingDeniedException("provider_not_configured",
                    "provider '" + providerId + "' is not in gpu-gateway.providers");
        }
        if (!provider.isEnabled()) {
            throw new RoutingDeniedException("provider_unavailable",
                    "provider '" + providerId + "' is disabled");
        }
        if (!provider.isUsable()) {
            throw new RoutingDeniedException("provider_not_configured",
                    "provider '" + providerId + "' has no base-url or credentials");
        }

        String providerModelId =
                modelCatalogService.resolveProviderModelId(logicalModelId, operation);

        return new RoutingDecision(
                logicalModelId, providerId, providerModelId, operation,
                provider.getBaseUrl(), provider.getApiKey(), RoutingDecision.MODE_EXTERNAL);
    }

    /**
     * Catalog lookup with canonical denial codes: a model known under a different
     * operation is a capability gap (§40), not a missing model.
     */
    private String resolveProviderIdChecked(String logicalModelId, Operation operation) {
        String providerId = modelCatalogService.resolveProviderId(logicalModelId, operation);
        if (providerId != null) {
            return providerId;
        }
        if (modelCatalogService.isKnownModel(logicalModelId)) {
            throw new RoutingDeniedException("capability_not_supported",
                    "model '" + logicalModelId + "' does not support operation " + operation);
        }
        throw new RoutingDeniedException("model_not_found",
                "model '" + logicalModelId + "' is not in the model catalog");
    }
}
