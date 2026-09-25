package org.synanton.gpu.domain.service;

import org.synanton.gpu.adapter.out.runtime.ModelCatalogService;
import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.model.RoutingDecision;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.Operation;
import org.synanton.gpu.v1.Provider;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #15 review §6 acceptance (routing layer): external-only never falls back to
 * local, the kill switch denies, unknown models/providers deny, and the catalog —
 * not the request — decides the provider.
 */
class ProviderRouterTest {

    private GpuGatewayProperties properties;
    private ProviderRouter router;

    @BeforeEach
    void setUp() {
        properties = new GpuGatewayProperties();
        properties.getDispatch().setStrategy("external");

        // catalog: mock-chat-1 (MOCK), gpt-4o-mini (OPENAI, mapped), mock-reranker-1 (MOCK)
        catalog("SYNTHESIZE", "mock-chat-1", "MOCK", "mock-chat-1-upstream");
        catalog("SYNTHESIZE", "gpt-4o-mini", "OPENAI", "openai/gpt-4o-mini");
        catalog("EMBED", "mock-embedding-1", "MOCK", "mock-embedding-1");
        catalog("RERANK", "mock-reranker-1", "MOCK", "mock-reranker-1");

        // registry: mock + openai
        provider("mock", "http://mock-provider:8080", "mock-key", true);
        provider("openai", "https://openrouter.ai/api/v1", "or-key", true);

        router = new ProviderRouter(properties, new ModelCatalogService(properties));
    }

    private void catalog(String operation, String logicalId, String provider, String providerModelId) {
        GpuGatewayProperties.ModelCatalog.OperationModels.ModelInfo info =
                new GpuGatewayProperties.ModelCatalog.OperationModels.ModelInfo();
        info.setProvider(provider);
        info.setProviderModelId(providerModelId);
        properties.getModelCatalog().getOperations()
                .computeIfAbsent(operation, k -> new GpuGatewayProperties.ModelCatalog.OperationModels())
                .getModels().put(logicalId, info);
    }

    private void provider(String id, String baseUrl, String apiKey, boolean enabled) {
        GpuGatewayProperties.ProviderConfig config = new GpuGatewayProperties.ProviderConfig();
        config.setBaseUrl(baseUrl);
        config.setApiKey(apiKey);
        config.setEnabled(enabled);
        properties.getProviders().put(id, config);
    }

    private ExecutionRequest request(String model, Operation operation) {
        return ExecutionRequest.newBuilder()
                .setRequestId("req-1")
                .setTenantId("tenant-abc")
                .setModel(model)
                .setModelVersion("1.0")
                .setOperation(operation)
                .setPayload(ByteString.copyFromUtf8("{}"))
                .build();
    }

    @Test
    void routesExternalModelToConfiguredProvider() {
        RoutingDecision decision = router.route(request("mock-chat-1", Operation.SYNTHESIZE));

        assertThat(decision.isLocal()).isFalse();
        assertThat(decision.providerId()).isEqualTo("mock");
        assertThat(decision.endpoint()).isEqualTo("http://mock-provider:8080");
        assertThat(decision.routingMode()).isEqualTo(RoutingDecision.MODE_EXTERNAL);
        assertThat(decision.toString()).doesNotContain("mock-key"); // credentials redacted
    }

    @Test
    void appliesLogicalToProviderModelMapping() {
        // review §6: "provider model mapping → logical ID rewritten upstream"
        RoutingDecision decision = router.route(request("gpt-4o-mini", Operation.SYNTHESIZE));

        assertThat(decision.providerId()).isEqualTo("openai");
        assertThat(decision.logicalModelId()).isEqualTo("gpt-4o-mini");
        assertThat(decision.providerModelId()).isEqualTo("openai/gpt-4o-mini");
    }

    @Test
    void externalModeDeniesLocalProviderRequests() {
        // review §6: "external-only + local model with same name → must not route locally"
        ExecutionRequest localRequest = ExecutionRequest.newBuilder(request("mock-chat-1", Operation.SYNTHESIZE))
                .setProvider(Provider.LOCAL)
                .build();

        assertThatThrownBy(() -> router.route(localRequest))
                .isInstanceOf(RoutingDeniedException.class)
                .extracting(e -> ((RoutingDeniedException) e).getCode())
                .isEqualTo("no_local_fallback");
    }

    @Test
    void killSwitchOffDeniesAllExternalRouting() {
        // review §6: "kill switch OFF → canonical denial"
        properties.getRouting().setExternalEnabled(false);

        assertThatThrownBy(() -> router.route(request("mock-chat-1", Operation.SYNTHESIZE)))
                .isInstanceOf(RoutingDeniedException.class)
                .extracting(e -> ((RoutingDeniedException) e).getCode())
                .isEqualTo("routing_disabled");
    }

    @Test
    void unknownModelDenied() {
        assertThatThrownBy(() -> router.route(request("no-such-model", Operation.SYNTHESIZE)))
                .isInstanceOf(RoutingDeniedException.class)
                .extracting(e -> ((RoutingDeniedException) e).getCode())
                .isEqualTo("model_not_found");
    }

    @Test
    void knownModelWithoutOperationIsCapabilityGap() {
        // review §6: "provider lacks rerank → capability_not_supported" (catalog-level)
        assertThatThrownBy(() -> router.route(request("gpt-4o-mini", Operation.RERANK)))
                .isInstanceOf(RoutingDeniedException.class)
                .extracting(e -> ((RoutingDeniedException) e).getCode())
                .isEqualTo("capability_not_supported");
    }

    @Test
    void disabledProviderDenied() {
        provider("mock", "http://mock-provider:8080", "mock-key", false);

        assertThatThrownBy(() -> router.route(request("mock-chat-1", Operation.SYNTHESIZE)))
                .isInstanceOf(RoutingDeniedException.class)
                .extracting(e -> ((RoutingDeniedException) e).getCode())
                .isEqualTo("provider_unavailable");
    }

    @Test
    void catalogProviderMissingFromRegistryDenied() {
        catalog("SYNTHESIZE", "ghost-model", "GHOST", "ghost");
        assertThatThrownBy(() -> router.route(request("ghost-model", Operation.SYNTHESIZE)))
                .isInstanceOf(RoutingDeniedException.class)
                .extracting(e -> ((RoutingDeniedException) e).getCode())
                .isEqualTo("provider_not_configured");
    }

    @Test
    void unsupportedStrategyFailsClosedAtStartup() {
        // §5.5: boot must fail, never silently fall back
        properties.getDispatch().setStrategy("openai"); // retired alias (review P0.4)

        assertThatThrownBy(() -> new ProviderRouter(properties, new ModelCatalogService(properties)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported gpu-gateway.dispatch.strategy");
    }

    @Test
    void externalStrategyWithoutProvidersFailsClosedAtStartup() {
        GpuGatewayProperties empty = new GpuGatewayProperties();
        empty.getDispatch().setStrategy("external");

        assertThatThrownBy(() -> new ProviderRouter(empty, new ModelCatalogService(empty)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("providers is empty");
    }

    @Test
    void localStrategiesKeepLocalPath() {
        GpuGatewayProperties local = new GpuGatewayProperties();
        local.getDispatch().setStrategy("direct");
        local.getDispatch().setVllmEndpoint("http://vllm-synthesis:8000");
        ProviderRouter localRouter = new ProviderRouter(local, new ModelCatalogService(local));

        RoutingDecision decision = localRouter.route(request("synanton-qwen3-4b-synthesis", Operation.SYNTHESIZE));

        assertThat(decision.isLocal()).isTrue();
        assertThat(decision.endpoint()).isEqualTo("http://vllm-synthesis:8000");
        assertThat(decision.providerModelId()).isEqualTo("synanton-qwen3-4b-synthesis");
    }

    @Test
    void catalogLocalModelInExternalModeIsNoLocalFallback() {
        // review §6: "external-only + local model with same name → must not route locally"
        catalog("SYNTHESIZE", "synanton-qwen3-4b-synthesis", "LOCAL", "synanton-qwen3-4b-synthesis");

        assertThatThrownBy(() -> router.route(request("synanton-qwen3-4b-synthesis", Operation.SYNTHESIZE)))
                .isInstanceOf(RoutingDeniedException.class)
                .extracting(e -> ((RoutingDeniedException) e).getCode())
                .isEqualTo("no_local_fallback");
    }

    @Test
    void providerWithoutCredentialsDenied() {
        provider("mock", "http://mock-provider:8080", "", true);

        assertThatThrownBy(() -> router.route(request("mock-chat-1", Operation.SYNTHESIZE)))
                .isInstanceOf(RoutingDeniedException.class)
                .extracting(e -> ((RoutingDeniedException) e).getCode())
                .isEqualTo("provider_not_configured");
    }
}
