package org.synanton.gpu.adapter.out.registry;

import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.model.ModelCapabilities;
import org.synanton.gpu.domain.port.out.ModelRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads model capability configuration at startup. Two sources, in precedence order:
 *
 * <ol>
 *   <li>{@code gpu-gateway.models.<id>} — explicit capabilities (GPU-5 local models;
 *       full control over concurrency limit and runtime class).</li>
 *   <li>{@code gpu-gateway.model-catalog.operations.<OP>.models.<id>} — GPU-7 catalog
 *       entries (PR #15 review P0.3/P0.4: a catalog model must be admissible — the
 *       deployment contract is "nothing advertised that can't resolve", plan §4.4).
 *       Catalog entries get a default concurrency limit and their provider id as the
 *       runtime class.</li>
 * </ol>
 *
 * Models in neither source are not served by this Gateway instance.
 */
@Component
public class ConfiguredModelRepository implements ModelRepository {

    /** Default admission concurrency for catalog (external) models. */
    static final int CATALOG_DEFAULT_CONCURRENCY = 8;

    private final Map<String, ModelCapabilities> capabilitiesMap;

    public ConfiguredModelRepository(GpuGatewayProperties properties) {
        Map<String, ModelCapabilities> map = new HashMap<>();

        properties.getModelCatalog().getOperations().values().forEach(operation ->
                operation.getModels().forEach((modelId, info) ->
                        map.putIfAbsent(modelId, new ModelCapabilities(
                                modelId,
                                CATALOG_DEFAULT_CONCURRENCY,
                                info.getMaxInputTokens(),
                                info.getProvider()))));

        properties.getModels().forEach((modelId, config) ->
                map.put(modelId, new ModelCapabilities(
                        modelId,
                        config.getConcurrencyLimit(),
                        config.getMaxInputTokens(),
                        config.getRuntimeClass())));

        this.capabilitiesMap = Map.copyOf(map);
    }

    @Override
    public Optional<ModelCapabilities> getCapabilities(String modelId) {
        return Optional.ofNullable(capabilitiesMap.get(modelId));
    }
}
