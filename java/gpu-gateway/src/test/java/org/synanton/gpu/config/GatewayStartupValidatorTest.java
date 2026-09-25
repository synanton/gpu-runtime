package org.synanton.gpu.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deployment Plan §5.5: inconsistent GPU-7 control configuration prevents startup. */
class GatewayStartupValidatorTest {

    private GpuGatewayProperties p;
    private GpuGatewayProperties.ModelCatalog.OperationModels.ModelInfo model;

    @BeforeEach
    void setUp() {
        p = new GpuGatewayProperties();
        // these tests target the GPU-7 control rules; the default security mode (mtls,
        // fail closed) needs TLS files and is covered by the security tests below
        p.getSecurity().setMode("insecure-plaintext");
        model = new GpuGatewayProperties.ModelCatalog.OperationModels.ModelInfo();
        model.setProvider("MOCK");
        model.setInputUsdPerMillion(BigDecimal.ONE);
        model.setOutputUsdPerMillion(BigDecimal.ONE);
        p.getModelCatalog().getOperations()
                .computeIfAbsent("SYNTHESIZE", k -> new GpuGatewayProperties.ModelCatalog.OperationModels())
                .getModels().put("m", model);
        var mock = new GpuGatewayProperties.ProviderConfig();
        mock.setBaseUrl("http://mock/v1");
        mock.setApiKey("k");
        p.getProviders().put("mock", mock);
        p.getBudget().setEnforcement("enabled");
        p.getBudget().setDefaultDailyUsd(new BigDecimal("5"));
        p.getUsage().setCostLedger("enabled");
    }

    @Test
    void consistentConfigurationPasses() {
        assertThatCode(() -> GatewayStartupValidator.validate(p)).doesNotThrowAnyException();
    }

    @Test
    void budgetWithoutCostLedgerFails() {
        p.getUsage().setCostLedger("disabled");
        assertThatThrownBy(() -> GatewayStartupValidator.validate(p)).hasMessageContaining("cost-ledger");
    }

    @Test
    void unpricedExternalModelWithBudgetFails() {
        model.setOutputUsdPerMillion(null);
        assertThatThrownBy(() -> GatewayStartupValidator.validate(p)).hasMessageContaining("price");
    }

    @Test
    void enabledProviderWithoutCredentialsFails() {
        p.getProviders().get("mock").setApiKey("");
        assertThatThrownBy(() -> GatewayStartupValidator.validate(p)).hasMessageContaining("credentials");
    }

    @Test
    void unknownEnforcementValueFails() {
        p.getBudget().setEnforcement("on");
        assertThatThrownBy(() -> GatewayStartupValidator.validate(p)).hasMessageContaining("enabled|disabled");
    }

    @Test
    void disabledProviderIsNotRequiredToHaveCredentials() {
        p.getProviders().get("mock").setApiKey("");
        p.getProviders().get("mock").setEnabled(false);
        assertThatCode(() -> GatewayStartupValidator.validate(p)).doesNotThrowAnyException();
    }

    @Test
    void mtlsIsTheDefaultAndFailsClosedWithoutTlsMaterial() {
        GpuGatewayProperties fresh = new GpuGatewayProperties();
        assertThatThrownBy(() -> GatewayStartupValidator.validate(fresh))
                .hasMessageContaining("security.tls.cert-chain");
    }

    @Test
    void mtlsRequiresRegisteredPrincipals(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        for (String f : new String[]{"s.crt", "s.key", "ca.crt"}) {
            java.nio.file.Files.writeString(dir.resolve(f), "x");
        }
        p.getSecurity().setMode("mtls");
        p.getSecurity().getTls().setCertChain(dir.resolve("s.crt").toString());
        p.getSecurity().getTls().setPrivateKey(dir.resolve("s.key").toString());
        p.getSecurity().getTls().setClientCa(dir.resolve("ca.crt").toString());
        assertThatThrownBy(() -> GatewayStartupValidator.validate(p)).hasMessageContaining("principals");
    }

    @Test
    void unknownSecurityModeFails() {
        p.getSecurity().setMode("tls-maybe");
        assertThatThrownBy(() -> GatewayStartupValidator.validate(p)).hasMessageContaining("mtls|insecure-plaintext");
    }
}
