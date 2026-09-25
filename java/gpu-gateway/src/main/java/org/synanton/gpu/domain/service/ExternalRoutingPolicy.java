package org.synanton.gpu.domain.service;

import org.synanton.gpu.adapter.out.runtime.ModelCatalogService;
import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.model.Execution;
import org.synanton.gpu.domain.model.ExecutionState;
import org.synanton.gpu.domain.model.ExecutionUsage;
import org.synanton.gpu.domain.model.LedgerEntry;
import org.synanton.gpu.domain.model.RoutingDecision;
import org.synanton.gpu.domain.port.out.CostLedger;
import org.synanton.gpu.domain.port.out.ProviderHealth;
import org.synanton.gpu.v1.ExecutionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * GPU-7 external-routing controls applied to an external {@link RoutingDecision}
 * before admission (Plan §11 step 6, §29) — all fail closed:
 * <ol>
 *   <li>sensitivity (T-K8S-49): a model tag or request {@code data_tags} entry in
 *       {@code sensitivity.block-external-tags} → {@code sensitive_model_external_blocked};</li>
 *   <li>provider health (T-K8S-46): unhealthy → {@code provider_unavailable};</li>
 *   <li>budget (T-K8S-48b): tenant's UTC-day spend ≥ limit → {@code budget_exceeded};
 *       ledger unreadable → {@code budget_state_unavailable}.</li>
 * </ol>
 * After a successful external execution it records the cost-ledger row (T-K8S-48).
 *
 * <p>Kept separate from {@link ProviderRouter} so the router's routing semantics stay
 * unchanged (PR #15 constraint); local decisions are never subject to these controls.
 */
@Component
@Slf4j
public class ExternalRoutingPolicy {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final GpuGatewayProperties properties;
    private final ModelCatalogService catalog;
    private final ProviderHealth providerHealth;
    private final CostLedger costLedger;
    private final RoutingControlService routingControl;

    /** Configuration-only controls (no persisted runtime overrides) — used in unit tests. */
    public ExternalRoutingPolicy(GpuGatewayProperties properties, ModelCatalogService catalog,
                                 ProviderHealth providerHealth, CostLedger costLedger) {
        this(properties, catalog, providerHealth, costLedger, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ExternalRoutingPolicy(GpuGatewayProperties properties, ModelCatalogService catalog,
                                 ProviderHealth providerHealth, CostLedger costLedger,
                                 RoutingControlService routingControl) {
        this.properties = properties;
        this.catalog = catalog;
        this.providerHealth = providerHealth;
        this.costLedger = costLedger;
        this.routingControl = routingControl;
    }

    public void check(RoutingDecision decision, ExecutionRequest request) {
        if (decision.isLocal()) {
            return;
        }
        if (routingControl != null) {
            // T-K8S-38 persisted runtime overrides (configuration was already applied by the router)
            if (!routingControl.externalRoutingEnabled()) {
                throw new RoutingDeniedException("routing_disabled",
                        "external routing kill switch is OFF (runtime control state)");
            }
            if (!routingControl.providerEnabled(decision.providerId())) {
                throw new RoutingDeniedException("provider_unavailable",
                        "provider '" + decision.providerId() + "' is disabled (runtime control state)");
            }
        }
        Set<String> blocked = lower(properties.getSensitivity().getBlockExternalTags());
        if (!blocked.isEmpty()) {
            Set<String> tags = lower(request.getDataTagsList());
            catalog.modelInfo(decision.logicalModelId(), decision.operation())
                    .ifPresent(info -> tags.addAll(lower(info.getTags())));
            tags.retainAll(blocked);
            if (!tags.isEmpty()) {
                throw new RoutingDeniedException("sensitive_model_external_blocked",
                        "sensitivity policy forbids external routing (tags " + tags + ")");
            }
        }
        if (!providerHealth.isHealthy(decision.providerId())) {
            throw new RoutingDeniedException("provider_unavailable",
                    "provider '" + decision.providerId() + "' is unhealthy");
        }
        GpuGatewayProperties.Budget budget = properties.getBudget();
        if (budget.isEnabled()) {
            BigDecimal limit = budget.limitFor(request.getTenantId());
            BigDecimal spent;
            try {
                spent = costLedger.spentToday(request.getTenantId());
            } catch (RuntimeException e) {
                log.error("Budget state unavailable for tenant={}", request.getTenantId(), e);
                throw new RoutingDeniedException("budget_state_unavailable",
                        "budget state could not be read; external routing denied");
            }
            if (spent.compareTo(limit) >= 0) {
                throw new RoutingDeniedException("budget_exceeded",
                        "tenant daily budget exhausted");
            }
        }
    }

    /** Records the ledger row for a successful external execution (idempotent per execution). */
    public void recordUsage(RoutingDecision decision, ExecutionRequest request, Execution execution) {
        if (decision.isLocal() || !properties.getUsage().isCostLedgerEnabled()
                || execution.state() != ExecutionState.SUCCEEDED) {
            return;
        }
        ExecutionUsage usage = execution.usage();
        BigDecimal cost = null;
        var info = catalog.modelInfo(decision.logicalModelId(), decision.operation());
        if (usage != null && info.isPresent() && info.get().getInputUsdPerMillion() != null
                && info.get().getOutputUsdPerMillion() != null) {
            cost = info.get().getInputUsdPerMillion().multiply(BigDecimal.valueOf(usage.inputTokens()))
                    .add(info.get().getOutputUsdPerMillion().multiply(BigDecimal.valueOf(usage.outputTokens())))
                    .divide(MILLION, MathContext.DECIMAL64);
        }
        try {
            costLedger.record(new LedgerEntry(execution.executionId(), request.getRequestId(),
                    request.getTenantId(), decision.providerId(), decision.logicalModelId(),
                    usage == null ? 0 : usage.inputTokens(), usage == null ? 0 : usage.outputTokens(), cost));
        } catch (RuntimeException e) {
            // The execution already completed; the budget is now stale for this tenant.
            log.error("Cost ledger write failed for execution_id={}", execution.executionId(), e);
        }
    }

    private static Set<String> lower(java.util.Collection<String> values) {
        Set<String> out = new HashSet<>();
        values.forEach(v -> out.add(v.toLowerCase(Locale.ROOT)));
        return out;
    }
}
