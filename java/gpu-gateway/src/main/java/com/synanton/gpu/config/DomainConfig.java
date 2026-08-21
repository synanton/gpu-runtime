package com.synanton.gpu.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

/** Wires domain-level and infrastructure beans that require explicit construction. */
@Configuration
public class DomainConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    /** Provides the vLLM endpoint URL to {@code DirectScheduler} and {@code VllmRuntime}. */
    @Bean
    public String vllmEndpointUrl(GpuGatewayProperties properties) {
        String endpoint = properties.getDispatch().getVllmEndpoint();
        return endpoint != null ? endpoint : "http://vllm-service:8000";
    }

    /** Lease window for RUNNING executions. Heartbeats must fire before this expires. */
    @Bean(name = "leaseTimeout")
    public Duration leaseTimeout(GpuGatewayProperties properties) {
        int seconds = properties.getExecution().getLeaseTimeoutSeconds();
        return seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ofSeconds(300);
    }

    /** Heartbeat interval for RUNNING executions. Must be less than {@link #leaseTimeout}. */
    @Bean(name = "heartbeatInterval")
    public Duration heartbeatInterval(GpuGatewayProperties properties) {
        int seconds = properties.getExecution().getHeartbeatIntervalSeconds();
        return seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ofSeconds(60);
    }

    /** Maximum time to wait for a model artifact to become available. */
    @Bean
    public Duration modelLoadTimeout(GpuGatewayProperties properties) {
        int ms = properties.getDispatch().getModelLoadTimeoutMs();
        return ms > 0 ? Duration.ofMillis(ms) : Duration.ofMinutes(10);
    }

    /** Per-request timeout for the vLLM HTTP call. */
    @Bean
    public Duration dispatchTimeout(GpuGatewayProperties properties) {
        int ms = properties.getDispatch().getTimeoutMs();
        return ms > 0 ? Duration.ofMillis(ms) : Duration.ofSeconds(120);
    }

    /** Root directory for the shared model artifact cache. */
    @Bean
    public Path modelCacheRoot(GpuGatewayProperties properties) {
        String cacheDir = properties.getArtifacts().getCacheDir();
        return Path.of(cacheDir != null ? cacheDir : "/model-cache");
    }

    /** Base URL of the internal model registry used by {@code SecureRegistryFetcher}. */
    @Bean
    public String registryBaseUrl(GpuGatewayProperties properties) {
        String url = properties.getArtifacts().getRegistryBaseUrl();
        return url != null ? url : "http://model-registry:8080";
    }

    /** Shared HTTP client for vLLM and registry requests. */
    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
