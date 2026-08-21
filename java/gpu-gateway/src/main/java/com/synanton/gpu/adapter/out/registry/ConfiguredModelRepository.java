package com.synanton.gpu.adapter.out.registry;

import com.synanton.gpu.config.GpuGatewayProperties;
import com.synanton.gpu.domain.model.ModelCapabilities;
import com.synanton.gpu.domain.port.out.ModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads model capability configuration from {@code application.yml} at startup.
 * Models not listed in the configuration are not served by this Gateway instance.
 */
@Component
public class ConfiguredModelRepository implements ModelRepository {

    private final Map<String, ModelCapabilities> capabilitiesMap;

    public ConfiguredModelRepository(GpuGatewayProperties properties) {
        this.capabilitiesMap = properties.getModels().entrySet().stream()
                .map(entry -> {
                    GpuGatewayProperties.ModelConfig config = entry.getValue();
                    return new ModelCapabilities(
                            entry.getKey(),
                            config.getConcurrencyLimit(),
                            config.getMaxInputTokens(),
                            config.getRuntimeClass()
                    );
                })
                .collect(Collectors.toMap(ModelCapabilities::modelId, Function.identity()));
    }

    @Override
    public Optional<ModelCapabilities> getCapabilities(String modelId) {
        return Optional.ofNullable(capabilitiesMap.get(modelId));
    }
}
