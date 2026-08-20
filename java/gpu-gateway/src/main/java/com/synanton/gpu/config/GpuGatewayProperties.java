package com.synanton.gpu.config;

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
    public Map<String, ModelConfig> getModels() { return models; }
    public void setModels(Map<String, ModelConfig> models) { this.models = models; }
}
