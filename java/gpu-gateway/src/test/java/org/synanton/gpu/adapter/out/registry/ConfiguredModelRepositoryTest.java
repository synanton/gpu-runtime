package org.synanton.gpu.adapter.out.registry;

import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.model.ModelCapabilities;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #15 review P0.3: catalog (GPU-7) models must be admissible — the deployment
 * contract is "nothing advertised that can't resolve". Explicit {@code gpu-gateway.models}
 * entries keep precedence over catalog-derived capabilities.
 */
class ConfiguredModelRepositoryTest {

    private GpuGatewayProperties propertiesWithCatalog() {
        GpuGatewayProperties properties = new GpuGatewayProperties();

        GpuGatewayProperties.ModelCatalog.OperationModels.ModelInfo info =
                new GpuGatewayProperties.ModelCatalog.OperationModels.ModelInfo();
        info.setProvider("MOCK");
        info.setProviderModelId("mock-chat-1");
        info.setMaxInputTokens(8192);

        GpuGatewayProperties.ModelCatalog.OperationModels ops =
                new GpuGatewayProperties.ModelCatalog.OperationModels();
        ops.getModels().put("mock-chat-1", info);
        properties.getModelCatalog().getOperations().put("SYNTHESIZE", ops);
        return properties;
    }

    @Test
    void catalogModelsAreAdmissible() {
        ConfiguredModelRepository repository = new ConfiguredModelRepository(propertiesWithCatalog());

        ModelCapabilities capabilities = repository.getCapabilities("mock-chat-1").orElseThrow();

        assertThat(capabilities.modelId()).isEqualTo("mock-chat-1");
        assertThat(capabilities.maxInputTokens()).isEqualTo(8192);
        assertThat(capabilities.concurrencyLimit())
                .isEqualTo(ConfiguredModelRepository.CATALOG_DEFAULT_CONCURRENCY);
        assertThat(capabilities.runtimeClass()).isEqualTo("MOCK");
    }

    @Test
    void explicitModelConfigWinsOverCatalog() {
        GpuGatewayProperties properties = propertiesWithCatalog();
        GpuGatewayProperties.ModelConfig explicit = new GpuGatewayProperties.ModelConfig();
        explicit.setConcurrencyLimit(3);
        explicit.setMaxInputTokens(4096);
        explicit.setRuntimeClass("vllm-local");
        properties.getModels().put("mock-chat-1", explicit);

        ConfiguredModelRepository repository = new ConfiguredModelRepository(properties);

        ModelCapabilities capabilities = repository.getCapabilities("mock-chat-1").orElseThrow();
        assertThat(capabilities.concurrencyLimit()).isEqualTo(3);
        assertThat(capabilities.maxInputTokens()).isEqualTo(4096);
        assertThat(capabilities.runtimeClass()).isEqualTo("vllm-local");
    }

    @Test
    void unknownModelsAreNotServed() {
        ConfiguredModelRepository repository = new ConfiguredModelRepository(propertiesWithCatalog());

        assertThat(repository.getCapabilities("no-such-model")).isEmpty();
    }
}
