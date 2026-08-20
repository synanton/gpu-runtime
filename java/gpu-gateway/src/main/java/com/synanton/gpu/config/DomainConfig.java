package com.synanton.gpu.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synanton.gpu.adapter.out.schedule.DirectScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

/** Wires domain-level beans that require explicit construction. */
@Configuration
public class DomainConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    /** Provides the vLLM endpoint URL from configuration to {@link DirectScheduler} and VllmRuntime. */
    @Bean
    public String vllmEndpointUrl(GpuGatewayProperties properties) {
        return properties.getDispatch().getVllmEndpoint();
    }

    /** Heartbeat interval for RUNNING executions. */
    @Bean
    public Duration heartbeatInterval(GpuGatewayProperties properties) {
        int seconds = properties.getExecution().getHeartbeatIntervalSeconds();
        return seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ofSeconds(60);
    }

    /** Maximum time to wait for a model to become READY before failing the execution. */
    @Bean
    public Duration modelLoadTimeout(GpuGatewayProperties properties) {
        int ms = properties.getDispatch().getModelLoadTimeoutMs();
        return ms > 0 ? Duration.ofMillis(ms) : Duration.ofMinutes(10);
    }

    /** Dispatch (vLLM inference) timeout. */
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

    /** Base URL of the internal model registry used by {@link com.synanton.gpu.adapter.out.registry.SecureRegistryFetcher}. */
    @Bean
    public String registryBaseUrl(GpuGatewayProperties properties) {
        String url = properties.getArtifacts().getRegistryBaseUrl();
        return url != null ? url : "http://model-registry:8080";
    }
}
