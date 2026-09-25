package org.synanton.gpu.adapter.out.jwt;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.synanton.gpu.config.GpuGatewayProperties;

/**
 * Readiness contributor {@code executionJwt}. When the GPU-5 execution JWT is enabled, it is
 * UP only once the keys are loaded and the JWKS listener is bound. The Gateway therefore
 * becomes ready before Envoy, which needs the JWKS (Deployment Plan §12). When disabled
 * (GPU-7, tests) it is always UP. Its details never include key material.
 */
public class ExecutionJwtHealthIndicator implements HealthIndicator {

    private final GpuGatewayProperties.ExecutionJwt config;
    private final ObjectProvider<JwksServer> jwksServer;

    public ExecutionJwtHealthIndicator(GpuGatewayProperties.ExecutionJwt config, ObjectProvider<JwksServer> jwksServer) {
        this.config = config;
        this.jwksServer = jwksServer;
    }

    @Override
    public Health health() {
        if (!config.isEnabled()) {
            return Health.up().withDetail("executionJwt", "disabled").build();
        }
        JwksServer server = jwksServer.getIfAvailable();
        if (server == null || !server.isRunning()) {
            return Health.outOfService().withDetail("jwks", "not listening").build();
        }
        return Health.up().withDetail("jwksPort", server.boundPort()).build();
    }
}
