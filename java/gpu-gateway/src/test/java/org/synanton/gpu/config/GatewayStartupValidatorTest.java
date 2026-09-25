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
}
