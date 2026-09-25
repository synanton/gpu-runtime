package org.synanton.gpu.domain.port.out;

/** Outbound port: provider health, tracked independently of Gateway health (T-K8S-46). */
public interface ProviderHealth {

    /**
     * False only when health checking is configured for the provider and the last probe
     * failed; providers without {@code health.path} are not probed and report true.
     */
    boolean isHealthy(String providerId);
}
