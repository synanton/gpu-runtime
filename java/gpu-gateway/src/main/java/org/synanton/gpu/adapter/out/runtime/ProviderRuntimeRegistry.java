package org.synanton.gpu.adapter.out.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.model.RoutingDecision;
import org.synanton.gpu.domain.model.RuntimeTarget;
import org.synanton.gpu.domain.port.out.ExecutionRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider registry (PR #15 review §5): resolves a {@link RoutingDecision}'s
 * provider id to a runtime, replacing the hard-coded OpenRouter special case.
 *
 * <p>Every configured provider ({@code gpu-gateway.providers.<id>}) gets a lazily
 * built {@link OpenAiProviderRuntime} with its own credentials, base URL, and
 * circuit breaker (state persists across calls because instances are cached).
 * OpenRouter's attribution headers live here as configuration, not code.
 */
@Component
@Slf4j
public class ProviderRuntimeRegistry {

    private final GpuGatewayProperties properties;
    private final Duration dispatchTimeout;
    private final ObjectMapper objectMapper;
    private final Map<String, OpenAiProviderRuntime> runtimes = new ConcurrentHashMap<>();

    public ProviderRuntimeRegistry(GpuGatewayProperties properties,
                                   Duration dispatchTimeout,
                                   ObjectMapper objectMapper) {
        this.properties = properties;
        this.dispatchTimeout = dispatchTimeout;
        this.objectMapper = objectMapper;
    }

    /** Runtime for a provider id; empty when the provider is not configured/enabled. */
    public Optional<ExecutionRuntime> get(String providerId) {
        GpuGatewayProperties.ProviderConfig config = properties.getProviders().get(providerId);
        if (config == null || !config.isEnabled()) {
            return Optional.empty();
        }
        return Optional.of(runtimes.computeIfAbsent(providerId, id -> build(id, config)));
    }

    /** The dispatch target for a routing decision: provider endpoint + provider model ID. */
    public RuntimeTarget targetFor(RoutingDecision decision) {
        return new RuntimeTarget(decision.endpoint(), decision.providerId(), decision.providerModelId());
    }

    private OpenAiProviderRuntime build(String providerId, GpuGatewayProperties.ProviderConfig config) {
        GpuGatewayProperties.ProviderConfig.CircuitBreaker cb = config.getCircuitBreaker();
        CircuitBreaker breaker = new CircuitBreaker(
                cb.getFailureThreshold(), Duration.ofSeconds(cb.getResetSeconds()));

        Map<String, String> extraHeaders = "openrouter".equals(providerId)
                ? Map.of("HTTP-Referer", "https://synanton.ai", "X-Title", "Synanton GPU Gateway")
                : Map.of();

        log.info("Provider runtime registered: id={} baseUrl={} (api key {})",
                providerId, config.getBaseUrl(),
                config.getApiKey() == null || config.getApiKey().isBlank() ? "absent" : "present");
        return new OpenAiProviderRuntime(
                providerId, config.getBaseUrl(), config.getApiKey(), extraHeaders,
                dispatchTimeout, breaker, objectMapper);
    }
}
