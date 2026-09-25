package org.synanton.gpu.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.synanton.gpu.adapter.out.jwt.ExecutionJwtHealthIndicator;
import org.synanton.gpu.adapter.out.jwt.ExecutionJwtKeys;
import org.synanton.gpu.adapter.out.jwt.ExecutionJwtSigner;
import org.synanton.gpu.adapter.out.jwt.JwksServer;

import java.nio.file.Path;
import java.time.Clock;

/**
 * GPU-5 execution JWT wiring (Deployment Plan §12, T-K8S-6a). With
 * {@code gpu-gateway.execution-jwt.enabled=true}, the keys are loaded at startup; any problem
 * fails the Gateway closed. The signer is used by the local runtime for every Gateway→Envoy
 * request, and the JWKS listener serves the public keys to Envoy.
 */
@Configuration
@Slf4j
public class ExecutionJwtConfig {

    private static final String ENABLED = "gpu-gateway.execution-jwt.enabled";

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    public ExecutionJwtKeys executionJwtKeys(GpuGatewayProperties properties) {
        GpuGatewayProperties.ExecutionJwt cfg = properties.getExecutionJwt();
        ExecutionJwtKeys keys = ExecutionJwtKeys.load(Path.of(cfg.getKeyDir()));
        log.info("Execution JWT keys loaded from {}: {}", cfg.getKeyDir(), keys); // kids only
        return keys;
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    public ExecutionJwtSigner executionJwtSigner(ExecutionJwtKeys keys, GpuGatewayProperties properties) {
        GpuGatewayProperties.ExecutionJwt cfg = properties.getExecutionJwt();
        return new ExecutionJwtSigner(keys, cfg.getIssuer(), cfg.getAudience(), cfg.getTtlSeconds(), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    public JwksServer jwksServer(ExecutionJwtKeys keys, GpuGatewayProperties properties) {
        return new JwksServer(properties.getExecutionJwt().getJwksPort(), keys);
    }

    /** Always registered, so the readiness group can include it (UP when the JWT is disabled). */
    @Bean
    public ExecutionJwtHealthIndicator executionJwtHealthIndicator(GpuGatewayProperties properties,
                                                                   ObjectProvider<JwksServer> jwksServer) {
        return new ExecutionJwtHealthIndicator(properties.getExecutionJwt(), jwksServer);
    }
}
