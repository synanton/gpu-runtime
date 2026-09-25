package org.synanton.gpu.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.synanton.gpu.domain.service.ProviderRouter;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Loads the shipped GPU-7 deployment config ({@code deployments/external/config/gateway-external.yaml})
 * with the real arm enabled and checks it passes the same fail-closed startup checks as the
 * Gateway: startup validation and the provider spend guard (allowed-model-pattern). It also
 * checks the retrieval-benchmark catalog arms and the benchmark principal (platform benchmark
 * §6 Phase B1-G, G3), so a catalog edit can't break startup unnoticed.
 */
class ExternalDeploymentConfigTest {

    private static final Path CONFIG = Path.of("../../deployments/external/config/gateway-external.yaml");
    private static final List<String> BENCHMARK_TENANTS = List.of(
            "rb-fixed-g", "rb-semantic-g", "rb-fixed-g-vl", "rb-semantic-g-vl", "rb-fixed-g-lfm", "rb-semantic-g-lfm");

    private GpuGatewayProperties p;

    @BeforeEach
    void load() throws Exception {
        assertThat(Files.isReadable(CONFIG)).as(CONFIG.toAbsolutePath().toString()).isTrue();
        StandardEnvironment env = new StandardEnvironment();
        // simulate compose: the opt-in real arm enabled with a (fake) key, and the mock key
        env.getPropertySources().addFirst(new MapPropertySource("test-env",
                Map.of("OPENAI_PROVIDER_ENABLED", "true", "OPENAI_API_KEY", "sk-test-not-a-key",
                        "MOCK_PROVIDER_API_KEY", "mock-test-key")));
        new YamlPropertySourceLoader().load("gateway-external", new FileSystemResource(CONFIG))
                .forEach(env.getPropertySources()::addLast);
        p = Binder.get(env).bind("gpu-gateway", GpuGatewayProperties.class).get();
    }

    @Test
    void shippedConfigPassesStartupValidationAndTheSpendGuard() {
        // TLS files are mounted at runtime; startup TLS checks are covered by MtlsAuthorizationTest
        p.getSecurity().setMode("insecure-plaintext");
        assertThatCode(() -> GatewayStartupValidator.validate(p)).doesNotThrowAnyException();
        assertThatCode(() -> new ProviderRouter(p, null)).doesNotThrowAnyException();
    }

    @Test
    void everyRealEmbeddingArmIsFreeAndDeclaresItsDimension() {
        var embed = p.getModelCatalog().getOperations().get("EMBED").getModels();
        assertThat(embed).containsKeys("synanton-free-embedding",
                "synanton-free-embedding-nemotron-vl", "synanton-free-embedding-lfm");
        embed.forEach((id, m) -> {
            if ("OPENAI".equalsIgnoreCase(m.getProvider())) {
                assertThat(m.getProviderModelId()).as(id).endsWith(":free");
                assertThat(m.getInputUsdPerMillion()).as(id).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(m.getProviderModelId()).as("logical ID ≠ provider ID").isNotEqualTo(id);
            }
        });
        // native dims measured in G0 (tools/gpu7-check/embed_probe.py)
        assertThat(embed.get("synanton-free-embedding").getEmbeddingDim()).isEqualTo(2048);
        assertThat(embed.get("synanton-free-embedding-nemotron-vl").getEmbeddingDim()).isEqualTo(2048);
        assertThat(embed.get("synanton-free-embedding-lfm").getEmbeddingDim()).isEqualTo(1024);
        assertThat(embed.get("synanton-free-embedding-lfm").getMaxInputTokens()).isEqualTo(512);
    }

    @Test
    void benchmarkPrincipalIsLimitedToExplicitBenchmarkTenants() {
        var principal = p.getSecurity().getPrincipals().get("synanton-benchmark");
        assertThat(principal).isNotNull();
        assertThat(principal.getTenants()).containsExactlyInAnyOrderElementsOf(BENCHMARK_TENANTS).doesNotContain("*");
        assertThat(principal.getRoles()).doesNotContain("admin");
        assertThat(principal.mayActFor("smoke-tenant")).isFalse();
        // defence in depth: a priced call would exhaust the benchmark tenants' budget at once
        BENCHMARK_TENANTS.forEach(t ->
                assertThat(p.getBudget().getTenantDailyUsd().get(t)).as(t).isNotNull().isLessThanOrEqualTo(new BigDecimal("0.000001")));
    }
}
