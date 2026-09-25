package org.synanton.gpu.domain.service;

import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.model.ControlOverride;
import org.synanton.gpu.domain.port.out.RoutingControlStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Effective GPU-7 routing control (Plan §32/§33/§38, T-K8S-38): configuration AND the
 * persisted runtime overrides in {@link RoutingControlStore}.
 *
 * <ul>
 *   <li>Configuration is the floor: a scope disabled in configuration stays disabled.</li>
 *   <li>Overrides survive restarts and are shared by all replicas; each replica re-reads
 *       them at most {@link #CACHE_TTL} after a change (the changing replica immediately).</li>
 *   <li>Fail closed: if the store cannot be read, external routing is treated as
 *       disabled ({@code routing_state_unavailable}).</li>
 * </ul>
 */
@Component
@Slf4j
public class RoutingControlService {

    static final Duration CACHE_TTL = Duration.ofSeconds(1);

    private final GpuGatewayProperties properties;
    private final RoutingControlStore store;
    private volatile Map<String, ControlOverride> cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public RoutingControlService(GpuGatewayProperties properties, RoutingControlStore store) {
        this.properties = properties;
        this.store = store;
    }

    /** Current overrides; throws {@link RoutingDeniedException} (routing_state_unavailable) if unreadable. */
    public Map<String, ControlOverride> overrides() {
        if (cached == null || Instant.now().isAfter(cachedAt.plus(CACHE_TTL))) {
            try {
                cached = Map.copyOf(store.all());
                cachedAt = Instant.now();
            } catch (RuntimeException e) {
                log.error("Routing control state unavailable — failing closed", e);
                throw new RoutingDeniedException("routing_state_unavailable",
                        "routing control state could not be read; external routing denied");
            }
        }
        return cached;
    }

    private boolean runtimeEnabled(String scope) {
        ControlOverride o = overrides().get(scope);
        return o == null || o.enabled();
    }

    public boolean externalRoutingEnabled() {
        return properties.getRouting().isExternalEnabled() && runtimeEnabled(ControlOverride.EXTERNAL_ROUTING);
    }

    public boolean providerEnabled(String providerId) {
        GpuGatewayProperties.ProviderConfig config = properties.getProviders().get(providerId);
        return config != null && config.isEnabled() && runtimeEnabled(ControlOverride.providerScope(providerId));
    }

    public void setExternalRouting(boolean enabled, String principal, String reason) {
        requireReason(reason);
        if (enabled && !properties.getRouting().isExternalEnabled()) {
            throw new RoutingDeniedException("config_disabled",
                    "external routing is disabled in configuration and cannot be enabled at runtime");
        }
        store.set(ControlOverride.EXTERNAL_ROUTING, enabled, principal, reason);
        cached = null; // this replica sees the change immediately
        log.warn("Routing control: external-routing enabled={} by={} reason={}", enabled, principal, reason);
    }

    public void setProviderEnabled(String providerId, boolean enabled, String principal, String reason) {
        requireReason(reason);
        GpuGatewayProperties.ProviderConfig config = properties.getProviders().get(providerId);
        if (config == null) {
            throw new RoutingDeniedException("provider_not_configured",
                    "provider '" + providerId + "' is not in gpu-gateway.providers");
        }
        if (enabled && !config.isEnabled()) {
            throw new RoutingDeniedException("config_disabled",
                    "provider '" + providerId + "' is disabled in configuration and cannot be enabled at runtime");
        }
        store.set(ControlOverride.providerScope(providerId), enabled, principal, reason);
        cached = null;
        log.warn("Routing control: provider={} enabled={} by={} reason={}", providerId, enabled, principal, reason);
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new RoutingDeniedException("invalid_request", "a reason is required for routing-control changes");
        }
    }
}
