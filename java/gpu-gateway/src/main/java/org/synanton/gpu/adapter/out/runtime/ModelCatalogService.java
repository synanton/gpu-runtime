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

    public ModelCatalogService(GpuGatewayProperties properties) {
        this.modelCatalog = properties.getModelCatalog();
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
     * Resolves the provider for a given model and operation.
     */
    public Provider resolveProvider(String modelId, Operation operation) {
        String operationKey = operation.name();
        if (modelCatalog.getOperations().containsKey(operationKey)) {
            GpuGatewayProperties.ModelCatalog.OperationModels opModels =
                    modelCatalog.getOperations().get(operationKey);
            if (opModels.getModels().containsKey(modelId)) {
                String providerStr = opModels.getModels().get(modelId).getProvider();
                return Provider.valueOf(providerStr);
            }
        }
        // Default to OPENROUTER for GPU-7
        return Provider.OPENROUTER;
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

                // Filter by provider if specified
                if (request.getProvider() != Provider.PROVIDER_UNSPECIFIED) {
                    Provider modelProvider = Provider.valueOf(modelInfo.getProvider());
                    if (modelProvider != request.getProvider()) {
                        continue;
                    }
                }

                // Check tenant access if tenant_id provided
                if (request.getTenantId() != null && !request.getTenantId().isEmpty()) {
                    if (!isModelAccessibleToTenant(modelId, request.getOperation(), request.getTenantId())) {
                        continue;
                    }
                }

                ModelInfo.Builder builder = ModelInfo.newBuilder()
                        .setModelId(modelId)
                        .setDisplayName(modelInfo.getDisplayName())
                        .setProvider(modelInfo.getProvider())
                        .setOperation(request.getOperation())
                        .setProviderModelId(modelInfo.getProviderModelId())
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