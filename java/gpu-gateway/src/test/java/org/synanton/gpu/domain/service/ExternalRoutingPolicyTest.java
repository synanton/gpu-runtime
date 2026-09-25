package org.synanton.gpu.domain.service;

import org.synanton.gpu.adapter.out.runtime.ModelCatalogService;
import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.model.*;
import org.synanton.gpu.domain.port.out.CostLedger;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PR #15 P1.3/P1.4: sensitivity, provider health, budget and cost ledger — all fail closed. */
class ExternalRoutingPolicyTest {

    private final List<LedgerEntry> ledger = new ArrayList<>();
    private BigDecimal spent = BigDecimal.ZERO;
    private boolean ledgerDown = false;
    private boolean healthy = true;
    private GpuGatewayProperties p;
    private ExternalRoutingPolicy policy;

    private final CostLedger fakeLedger = new CostLedger() {
        @Override public void record(LedgerEntry e) { ledger.add(e); }
        @Override public BigDecimal spentToday(String tenant) {
            if (ledgerDown) throw new IllegalStateException("db down");
            return spent;
        }
    };

    @BeforeEach
    void setUp() {
        p = new GpuGatewayProperties();
        p.getDispatch().setStrategy("external");
        model("chat", List.of());
        model("chat-sensitive", List.of("Sensitive"));
        p.getSensitivity().setBlockExternalTags(List.of("sensitive", "pii"));
        p.getBudget().setEnforcement("enabled");
        p.getBudget().setDefaultDailyUsd(new BigDecimal("5.00"));
        p.getUsage().setCostLedger("enabled");
        policy = new ExternalRoutingPolicy(p, new ModelCatalogService(p), id -> healthy, fakeLedger);
    }

    private void model(String id, List<String> tags) {
        var info = new GpuGatewayProperties.ModelCatalog.OperationModels.ModelInfo();
        info.setProvider("MOCK");
        info.setProviderModelId("mock-chat-1");
        info.setTags(tags);
        info.setInputUsdPerMillion(new BigDecimal("1.00"));
        info.setOutputUsdPerMillion(new BigDecimal("2.00"));
        p.getModelCatalog().getOperations()
                .computeIfAbsent("SYNTHESIZE", k -> new GpuGatewayProperties.ModelCatalog.OperationModels())
                .getModels().put(id, info);
    }

    private static RoutingDecision external(String model) {
        return new RoutingDecision(model, "mock", "mock-chat-1", Operation.SYNTHESIZE,
                "http://mock/v1", "k", RoutingDecision.MODE_EXTERNAL);
    }

    private static ExecutionRequest request(String model, String... dataTags) {
        return ExecutionRequest.newBuilder().setRequestId("r").setTenantId("tenant-a").setModel(model)
                .setModelVersion("1").setOperation(Operation.SYNTHESIZE)
                .addAllDataTags(List.of(dataTags)).build();
    }

    private static String code(Runnable r) {
        try {
            r.run();
            return "allowed";
        } catch (RoutingDeniedException e) {
            return e.getCode();
        }
    }

    @Test
    void sensitiveModelTagBlocksExternalRouting() {
        assertThat(code(() -> policy.check(external("chat-sensitive"), request("chat-sensitive"))))
                .isEqualTo("sensitive_model_external_blocked");
    }

    @Test
    void sensitiveRequestDataTagBlocksExternalRouting() {
        assertThat(code(() -> policy.check(external("chat"), request("chat", "PII"))))
                .isEqualTo("sensitive_model_external_blocked");
    }

    @Test
    void unhealthyProviderIsDenied() {
        healthy = false;
        assertThat(code(() -> policy.check(external("chat"), request("chat")))).isEqualTo("provider_unavailable");
    }

    @Test
    void exhaustedBudgetIsDenied() {
        spent = new BigDecimal("5.00");
        assertThat(code(() -> policy.check(external("chat"), request("chat")))).isEqualTo("budget_exceeded");
    }

    @Test
    void unreadableBudgetStateFailsClosed() {
        ledgerDown = true;
        assertThat(code(() -> policy.check(external("chat"), request("chat")))).isEqualTo("budget_state_unavailable");
    }

    @Test
    void perTenantBudgetOverridesDefault() {
        p.getBudget().getTenantDailyUsd().put("tenant-a", new BigDecimal("0.01"));
        spent = new BigDecimal("0.02");
        assertThat(code(() -> policy.check(external("chat"), request("chat")))).isEqualTo("budget_exceeded");
    }

    @Test
    void localDecisionsAreNotSubjectToExternalControls() {
        healthy = false;
        spent = new BigDecimal("100");
        assertThatCode(() -> policy.check(RoutingDecision.local("chat-sensitive", Operation.SYNTHESIZE, "http://envoy"),
                request("chat-sensitive", "pii"))).doesNotThrowAnyException();
    }

    private static Execution execution(ExecutionState state, ExecutionUsage usage) {
        return new Execution("e1", "r", "h", "tenant-a", "chat", state, "MOCK",
                Instant.now(), Instant.now(), null, null, usage, null, new byte[0]);
    }

    @Test
    void successfulExecutionRecordsLedgerRowWithCost() {
        policy.recordUsage(external("chat"), request("chat"),
                execution(ExecutionState.SUCCEEDED, new ExecutionUsage(1000, 500, 0.1, "mock")));

        assertThat(ledger).singleElement().satisfies(e -> {
            assertThat(e.tenantId()).isEqualTo("tenant-a");
            assertThat(e.providerId()).isEqualTo("mock");
            assertThat(e.costUsd()).isEqualByComparingTo("0.002"); // (1000*1 + 500*2) / 1e6
        });
    }

    @Test
    void unavailableUsageRecordsNullCostNeverZero() {
        policy.recordUsage(external("chat"), request("chat"), execution(ExecutionState.SUCCEEDED, null));

        assertThat(ledger).singleElement().satisfies(e -> assertThat(e.costUsd()).isNull());
    }

    @Test
    void failedExecutionsAreNotLedgered() {
        policy.recordUsage(external("chat"), request("chat"), execution(ExecutionState.FAILED, null));
        assertThat(ledger).isEmpty();
    }
}
