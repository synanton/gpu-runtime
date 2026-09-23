package org.synanton.gpu.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/** Typed configuration for the GPU Gateway service. */
@ConfigurationProperties(prefix = "gpu-gateway")
public class GpuGatewayProperties {

    private int grpcPort;
    private int maxInboundMessageSizeBytes;
    private Dispatch dispatch = new Dispatch();
    private Execution execution = new Execution();
    private Artifacts artifacts = new Artifacts();
    private Providers providers = new Providers();
    private ModelCatalog modelCatalog = new ModelCatalog();
    private Map<String, ModelConfig> models = new HashMap<>();

    public static class Dispatch {
        private String strategy;
        private String vllmEndpoint;
        private int timeoutMs;
        private int modelLoadTimeoutMs;

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public String getVllmEndpoint() { return vllmEndpoint; }
        public void setVllmEndpoint(String vllmEndpoint) { this.vllmEndpoint = vllmEndpoint; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getModelLoadTimeoutMs() { return modelLoadTimeoutMs; }
        public void setModelLoadTimeoutMs(int modelLoadTimeoutMs) {
            this.modelLoadTimeoutMs = modelLoadTimeoutMs;
        }
    }

    public static class Providers {
        private OpenRouter openrouter = new OpenRouter();

        public OpenRouter getOpenrouter() { return openrouter; }
        public void setOpenrouter(OpenRouter openrouter) { this.openrouter = openrouter; }

        public static class OpenRouter {
            private String apiKey;
            private String baseUrl = "https://openrouter.ai/api/v1";
            private String defaultModel;
            private Map<String, String> modelMapping = new HashMap<>();

            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getDefaultModel() { return defaultModel; }
            public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
            public Map<String, String> getModelMapping() { return modelMapping; }
            public void setModelMapping(Map<String, String> modelMapping) { this.modelMapping = modelMapping; }
        }
    }

    public static class ModelCatalog {
        private Map<String, OperationModels> operations = new HashMap<>();
        private Map<String, TenantModelOverrides> tenants = new HashMap<>();

        public Map<String, OperationModels> getOperations() { return operations; }
        public void setOperations(Map<String, OperationModels> operations) { this.operations = operations; }
        public Map<String, TenantModelOverrides> getTenants() { return tenants; }
        public void setTenants(Map<String, TenantModelOverrides> tenants) { this.tenants = tenants; }

        public static class OperationModels {
            private String defaultModel;
            private Map<String, ModelInfo> models = new HashMap<>();

            public String getDefaultModel() { return defaultModel; }
            public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
            public Map<String, ModelInfo> getModels() { return models; }
            public void setModels(Map<String, ModelInfo> models) { this.models = models; }

            public static class ModelInfo {
                private String providerModelId;
                private String displayName;
                private String provider = "OPENROUTER";
                private boolean isDefault = false;
                private int maxInputTokens = 8192;
                private int maxOutputTokens = 2048;
                private int embeddingDim = 1536;

                public String getProviderModelId() { return providerModelId; }
                public void setProviderModelId(String providerModelId) { this.providerModelId = providerModelId; }
                public String getDisplayName() { return displayName; }
                public void setDisplayName(String displayName) { this.displayName = displayName; }
                public String getProvider() { return provider; }
                public void setProvider(String provider) { this.provider = provider; }
                public boolean isDefault() { return isDefault; }
                public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
                public int getMaxInputTokens() { return maxInputTokens; }
                public void setMaxInputTokens(int maxInputTokens) { this.maxInputTokens = maxInputTokens; }
                public int getMaxOutputTokens() { return maxOutputTokens; }
                public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
                public int getEmbeddingDim() { return embeddingDim; }
                public void setEmbeddingDim(int embeddingDim) { this.embeddingDim = embeddingDim; }
            }
        }

        public static class TenantModelOverrides {
            private Map<String, String> modelOverrides = new HashMap<>();

            public Map<String, String> getModelOverrides() { return modelOverrides; }
            public void setModelOverrides(Map<String, String> modelOverrides) { this.modelOverrides = modelOverrides; }
        }
    }

    public static class Execution {
        private int leaseTimeoutSeconds;
        private int heartbeatIntervalSeconds;

        public int getLeaseTimeoutSeconds() { return leaseTimeoutSeconds; }
        public void setLeaseTimeoutSeconds(int leaseTimeoutSeconds) {
            this.leaseTimeoutSeconds = leaseTimeoutSeconds;
        }
        public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
        public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
            this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        }
    }

    public static class Artifacts {
        private String cacheDir;
        private String registryBaseUrl;

        public String getCacheDir() { return cacheDir; }
        public void setCacheDir(String cacheDir) { this.cacheDir = cacheDir; }
        public String getRegistryBaseUrl() { return registryBaseUrl; }
        public void setRegistryBaseUrl(String registryBaseUrl) {
            this.registryBaseUrl = registryBaseUrl;
        }
    }

    public static class ModelConfig {
        private int concurrencyLimit;
        private int maxInputTokens;
        private String runtimeClass;
        private String digest;

        public int getConcurrencyLimit() { return concurrencyLimit; }
        public void setConcurrencyLimit(int concurrencyLimit) {
            this.concurrencyLimit = concurrencyLimit;
        }
        public int getMaxInputTokens() { return maxInputTokens; }
        public void setMaxInputTokens(int maxInputTokens) { this.maxInputTokens = maxInputTokens; }
        public String getRuntimeClass() { return runtimeClass; }
        public void setRuntimeClass(String runtimeClass) { this.runtimeClass = runtimeClass; }
        public String getDigest() { return digest; }
        public void setDigest(String digest) { this.digest = digest; }
    }

    public int getGrpcPort() { return grpcPort; }
    public void setGrpcPort(int grpcPort) { this.grpcPort = grpcPort; }
    public int getMaxInboundMessageSizeBytes() { return maxInboundMessageSizeBytes; }
    public void setMaxInboundMessageSizeBytes(int maxInboundMessageSizeBytes) {
        this.maxInboundMessageSizeBytes = maxInboundMessageSizeBytes;
    }
    public Dispatch getDispatch() { return dispatch; }
    public void setDispatch(Dispatch dispatch) { this.dispatch = dispatch; }
    public Execution getExecution() { return execution; }
    public void setExecution(Execution execution) { this.execution = execution; }
    public Artifacts getArtifacts() { return artifacts; }
    public void setArtifacts(Artifacts artifacts) { this.artifacts = artifacts; }
    public Providers getProviders() { return providers; }
    public void setProviders(Providers providers) { this.providers = providers; }
    public ModelCatalog getModelCatalog() { return modelCatalog; }
    public void setModelCatalog(ModelCatalog modelCatalog) { this.modelCatalog = modelCatalog; }
    public Map<String, ModelConfig> getModels() { return models; }
    public void setModels(Map<String, ModelConfig> models) { this.models = models; }
}
