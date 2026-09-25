package org.synanton.gpu.adapter.out.runtime;

import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.v1.GetModelsRequest;
import org.synanton.gpu.v1.ModelInfo;
import org.synanton.gpu.v1.Operation;
import org.synanton.gpu.v1.Provider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan §4.4: advertised ⇔ resolvable; provider model IDs never exposed downstream. */
class ModelCatalogServiceTest {

    private static GpuGatewayProperties properties(String strategy) {
        GpuGatewayProperties p = new GpuGatewayProperties();
        p.getDispatch().setStrategy(strategy);
        add(p, "synanton-qwen3-4b-synthesis", "LOCAL", "synanton-qwen3-4b-synthesis");
        add(p, "synanton-mock-chat", "MOCK", "mock-chat-1");
        add(p, "gpt-4o-mini", "OPENAI", "openai/gpt-4o-mini");
        GpuGatewayProperties.ProviderConfig mock = new GpuGatewayProperties.ProviderConfig();
        mock.setBaseUrl("http://mock:8080/v1");
        mock.setApiKey("k");
        p.getProviders().put("mock", mock);
        GpuGatewayProperties.ProviderConfig openai = new GpuGatewayProperties.ProviderConfig();
        openai.setBaseUrl("https://openrouter.ai/api/v1");
        openai.setApiKey(""); // no credentials → not resolvable → not advertised
        p.getProviders().put("openai", openai);
        return p;
    }

    private static void add(GpuGatewayProperties p, String id, String provider, String providerModelId) {
        var info = new GpuGatewayProperties.ModelCatalog.OperationModels.ModelInfo();
        info.setProvider(provider);
        info.setProviderModelId(providerModelId);
        info.setDisplayName(id);
        p.getModelCatalog().getOperations()
                .computeIfAbsent("SYNTHESIZE", k -> new GpuGatewayProperties.ModelCatalog.OperationModels())
                .getModels().put(id, info);
    }

    private static List<ModelInfo> models(GpuGatewayProperties p, Provider filter) {
        return new ModelCatalogService(p).getModels(GetModelsRequest.newBuilder()
                .setOperation(Operation.SYNTHESIZE).setProvider(filter).build()).getModelsList();
    }

    @Test
    void externalModeAdvertisesOnlyUsableExternalProviders() {
        List<ModelInfo> models = models(properties("external"), Provider.PROVIDER_UNSPECIFIED);

        assertThat(models).extracting(ModelInfo::getModelId).containsExactly("synanton-mock-chat");
    }

    @Test
    void localModeAdvertisesLocalModels() {
        List<ModelInfo> models = models(properties("direct"), Provider.PROVIDER_UNSPECIFIED);

        assertThat(models).extracting(ModelInfo::getModelId)
                .containsExactlyInAnyOrder("synanton-qwen3-4b-synthesis", "synanton-mock-chat");
    }

    @Test
    @SuppressWarnings("deprecation") // asserting the deprecated field stays empty
    void providerModelIdIsNeverExposed() {
        List<ModelInfo> models = models(properties("external"), Provider.PROVIDER_UNSPECIFIED);

        assertThat(models).allSatisfy(m -> assertThat(m.getProviderModelId()).isEmpty());
    }

    @Test
    void providerFilterDoesNotCrashOnConfiguredProvidersOutsideTheWireEnum() {
        // previously Provider.valueOf("MOCK") threw IllegalArgumentException
        List<ModelInfo> models = models(properties("direct"), Provider.LOCAL);

        assertThat(models).extracting(ModelInfo::getModelId).containsExactly("synanton-qwen3-4b-synthesis");
    }

    @Test
    void entriesWithoutDisplayNameAreAdvertisedUnderTheirModelId() {
        // regression: protobuf setDisplayName(null) threw NPE → GetModels INTERNAL
        GpuGatewayProperties p = properties("external");
        p.getModelCatalog().getOperations().get("SYNTHESIZE").getModels().get("synanton-mock-chat").setDisplayName(null);

        assertThat(models(p, Provider.PROVIDER_UNSPECIFIED))
                .extracting(ModelInfo::getDisplayName).containsExactly("synanton-mock-chat");
    }
}
