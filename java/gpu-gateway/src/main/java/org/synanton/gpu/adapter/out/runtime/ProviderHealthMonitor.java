package org.synanton.gpu.adapter.out.runtime;

import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.port.out.ProviderHealth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Provider health (T-K8S-46), tracked independently of Gateway health. Every usable
 * provider with {@code health.path} is probed ({@code GET <base-url><path>}, provider
 * credentials) every {@code health.interval-seconds}; the first probe runs at startup.
 * {@code health.failure-threshold} consecutive failed probes (default 2) mark the provider
 * unhealthy → new requests are denied
 * {@code provider_unavailable} until a probe succeeds (fail closed). Providers without
 * {@code health.path} are not probed.
 */
@Component
@Slf4j
public class ProviderHealthMonitor implements ProviderHealth, SmartLifecycle {

    private final GpuGatewayProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Boolean> healthy = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService scheduler;

    public ProviderHealthMonitor(GpuGatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isHealthy(String providerId) {
        return healthy.getOrDefault(providerId, true);
    }

    /** One probe of one provider; package-visible for tests. */
    boolean probe(String providerId, GpuGatewayProperties.ProviderConfig config) {
        boolean ok;
        String reason;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getBaseUrl() + config.getHealth().getPath()))
                    .timeout(Duration.ofSeconds(5)).GET();
            if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                request.header("Authorization", "Bearer " + config.getApiKey());
            }
            config.getHeaders().forEach(request::header);
            int status = httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            ok = status == 200;
            reason = "HTTP " + status;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ok = false;
            reason = "interrupted";
        } catch (Exception e) {
            ok = false;
            reason = e.getClass().getSimpleName();
        }
        int failures = ok ? 0 : consecutiveFailures.merge(providerId, 1, Integer::sum);
        if (ok) {
            consecutiveFailures.put(providerId, 0);
        }
        // one transient miss does not take a provider out; N consecutive misses do (fail closed)
        boolean nowHealthy = ok || failures < Math.max(1, config.getHealth().getFailureThreshold());
        Boolean previous = healthy.put(providerId, nowHealthy);
        if (previous == null || previous != nowHealthy || !ok) {
            log.info("Provider health: id={} healthy={} probe={} consecutiveFailures={}",
                    providerId, nowHealthy, reason, failures);
        }
        return nowHealthy;
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "provider-health");
            t.setDaemon(true);
            return t;
        });
        properties.getProviders().forEach((id, config) -> {
            String path = config.getHealth().getPath();
            if (!config.isUsable() || path == null || path.isBlank()) {
                return;
            }
            probe(id, config); // first probe synchronously: health is known before readiness
            long interval = Math.max(5, config.getHealth().getIntervalSeconds());
            scheduler.scheduleWithFixedDelay(() -> probe(id, config), interval, interval, TimeUnit.SECONDS);
        });
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }
}
