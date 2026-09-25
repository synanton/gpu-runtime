package org.synanton.gpu.adapter.out.runtime;

import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.v1.GetModelsRequest;
import org.synanton.gpu.v1.GetModelsResponse;
import org.synanton.gpu.v1.ModelInfo;
import org.synanton.gpu.v1.Operation;
import org.synanton.gpu.v1.Provider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for managing the model catalog and resolving tenant-specific models.
 */
@Component
public class ModelCatalogService {

    private final GpuGatewayProperties.ModelCatalog modelCatalog;
    private final GpuGatewayProperties properties;

    public ModelCatalogService(GpuGatewayProperties properties) {
        this.modelCatalog = properties.getModelCatalog();
        this.properties = properties;
    }

    /**
     * Plan §4.4: a model is advertised only if the active deployment can resolve it —
     * LOCAL models only in local modes; external models only when their provider is
     * usable (configured, enabled, base URL + credentials). Same rule as ProviderRouter.
     */
    boolean isAdvertisable(GpuGatewayProperties.ModelCatalog.OperationModels.ModelInfo info) {
        String provider = info.getProvider() == null ? "" : info.getProvider().toLowerCase(java.util.Locale.ROOT);
        boolean external = "external".equals(properties.getDispatch().getStrategy());
        if ("local".equals(provider)) {
            return !external;
        }
        GpuGatewayProperties.ProviderConfig config = properties.getProviders().get(provider);
        if (config != null && config.isUsable()) {
            return true;
        }
        // T-K8S-52: servable through a usable external fallback
        return info.getFallbacks().stream().anyMatch(fb -> {
            GpuGatewayProperties.ProviderConfig c = fb.getProvider() == null ? null
                    : properties.getProviders().get(fb.getProvider().toLowerCase(java.util.Locale.ROOT));
            return c != null && c.isUsable();
        });
    }

    /**
     * Resolves the model ID for a given tenant and operation.
     * Checks tenant overrides first, then falls back to operation default.
     */
    public String resolveModel(String tenantId, Operation operation) {
        // Check tenant-specific override
        if (tenantId != null && modelCatalog.getTenants().containsKey(tenantId)) {
            GpuGatewayProperties.ModelCatalog.TenantModelOverrides tenantOverrides =
                    modelCatalog.getTenants().get(tenantId);
            String operationKey = operation.name();
            if (tenantOverrides.getModelOverrides().containsKey(operationKey)) {
                return tenantOverrides.getModelOverrides().get(operationKey);
            }
        }

        // Fall back to operation default
        String operationKey = operation.name();
        if (modelCatalog.getOperations().containsKey(operationKey)) {
            GpuGatewayProperties.ModelCatalog.OperationModels opModels =
                    modelCatalog.getOperations().get(operationKey);
            if (opModels.getDefaultModel() != null) {
                return opModels.getDefaultModel();
            }
        }

        // Ultimate fallback
        return switch (operation) {
            case SYNTHESIZE -> "llama-3.1-8b-instruct";
            case EMBED -> "text-embedding-3-small";
            case RERANK -> "ms-marco-MiniLM-L-6-v2";
            default -> "llama-3.1-8b-instruct";
        };
    }

    /**
     * Resolves the configured provider id (e.g. {@code openai}, {@code mock}) for a
     * logical model under an operation. Returns {@code null} when the model is not
     * registered for that operation — callers must fail closed, never default.
     *
     * <p>Replaces the old proto-enum-based {@code resolveProvider}, which crashed with
     * {@code IllegalArgumentException} on any provider not in the wire enum (e.g. MOCK)
     * and silently defaulted to OPENAI (PR #15 review P0.3).
     */
    public String resolveProviderId(String modelId, Operation operation) {
        String operationKey = operation.name();
        if (modelCatalog.getOperations().containsKey(operationKey)) {
            GpuGatewayProperties.ModelCatalog.OperationModels opModels =
                    modelCatalog.getOperations().get(operationKey);
            if (opModels.getModels().containsKey(modelId)) {
                return opModels.getModels().get(modelId).getProvider();
            }
        }
        return null;
    }

    /** Catalog entry of a logical model under an operation, if registered. */
    public Optional<GpuGatewayProperties.ModelCatalog.OperationModels.ModelInfo> modelInfo(
            String modelId, Operation operation) {
        GpuGatewayProperties.ModelCatalog.OperationModels ops =
                modelCatalog.getOperations().get(operation.name());
        return ops == null ? Optional.empty() : Optional.ofNullable(ops.getModels().get(modelId));
    }

    /** True when the logical model is registered under at least one operation. */
    public boolean isKnownModel(String modelId) {
        return modelCatalog.getOperations().values().stream()
                .anyMatch(op -> op.getModels().containsKey(modelId));
    }

    /**
     * Resolves the provider-specific model ID for a given logical model ID.
     */
    public String resolveProviderModelId(String modelId, Operation operation) {
        String operationKey = operation.name();
        if (modelCatalog.getOperations().containsKey(operationKey)) {
            GpuGatewayProperties.ModelCatalog.OperationModels opModels =
                    modelCatalog.getOperations().get(operationKey);
            if (opModels.getModels().containsKey(modelId)) {
                return opModels.getModels().get(modelId).getProviderModelId();
            }
        }
        return modelId;
    }

    /**
     * Gets available models for the given operation and provider, filtered by tenant.
     */
    public GetModelsResponse getModels(GetModelsRequest request) {
        List<ModelInfo> models = new ArrayList<>();
        String operationKey = request.getOperation().name();

        if (modelCatalog.getOperations().containsKey(operationKey)) {
            GpuGatewayProperties.ModelCatalog.OperationModels opModels =
                    modelCatalog.getOperations().get(operationKey);

            for (Map.Entry<String, GpuGatewayProperties.ModelCatalog.OperationModels.ModelInfo> entry :
                    opModels.getModels().entrySet()) {

                String modelId = entry.getKey();
                GpuGatewayProperties.ModelCatalog.OperationModels.ModelInfo modelInfo = entry.getValue();

                if (!isAdvertisable(modelInfo)) {
                    continue;
                }
                // Filter by provider if specified (name match: configured providers such
                // as MOCK are not in the wire enum, so Provider.valueOf would throw)
                if (request.getProvider() != Provider.PROVIDER_UNSPECIFIED
                        && !request.getProvider().name().equalsIgnoreCase(modelInfo.getProvider())) {
                    continue;
                }

                // Check tenant access if tenant_id provided
                if (request.getTenantId() != null && !request.getTenantId().isEmpty()) {
                    if (!isModelAccessibleToTenant(modelId, request.getOperation(), request.getTenantId())) {
                        continue;
                    }
                }

                ModelInfo.Builder builder = ModelInfo.newBuilder()
                        .setModelId(modelId)
                        // display-name is optional in config; protobuf setters reject null
                        .setDisplayName(modelInfo.getDisplayName() != null ? modelInfo.getDisplayName() : modelId)
                        .setProvider(modelInfo.getProvider() != null ? modelInfo.getProvider() : "")
                        .setOperation(request.getOperation())
                        // provider_model_id is deliberately NOT populated: provider model
                        // IDs are never exposed downstream (PR #15 invariant 3).
                        .setIsDefault(modelInfo.isDefault())
                        .setMaxInputTokens(modelInfo.getMaxInputTokens())
                        .setMaxOutputTokens(modelInfo.getMaxOutputTokens())
                        .setEmbeddingDim(modelInfo.getEmbeddingDim());

                models.add(builder.build());
            }
        }

        return GetModelsResponse.newBuilder().addAllModels(models).build();
    }

    private boolean isModelAccessibleToTenant(String modelId, Operation operation, String tenantId) {
        // If tenant has overrides, only those models are accessible
        if (modelCatalog.getTenants().containsKey(tenantId)) {
            GpuGatewayProperties.ModelCatalog.TenantModelOverrides tenantOverrides =
                    modelCatalog.getTenants().get(tenantId);
            String operationKey = operation.name();
            // If tenant has specific override for this operation, only that model is accessible
            if (tenantOverrides.getModelOverrides().containsKey(operationKey)) {
                return tenantOverrides.getModelOverrides().get(operationKey).equals(modelId);
            }
        }
        // Otherwise all models for the operation are accessible
        return true;
    }

    /**
     * Gets the default model for an operation.
     */
    public String getDefaultModel(Operation operation) {
        String operationKey = operation.name();
        if (modelCatalog.getOperations().containsKey(operationKey)) {
            GpuGatewayProperties.ModelCatalog.OperationModels opModels =
                    modelCatalog.getOperations().get(operationKey);
            return opModels.getDefaultModel();
        }
        return switch (operation) {
            case SYNTHESIZE -> "llama-3.1-8b-instruct";
            case EMBED -> "text-embedding-3-small";
            case RERANK -> "ms-marco-MiniLM-L-6-v2";
            default -> "llama-3.1-8b-instruct";
        };
    }
}