package org.synanton.gpu.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Deployment Plan §5.5 startup validation for the GPU-7 controls (PR #15 P1.3): an
 * inconsistent configuration prevents the Gateway from starting (fail closed).
 * Strategy and allowed-model-pattern checks live in {@code ProviderRouter}.
 */
@Component
@Slf4j
public class GatewayStartupValidator {

    private static final Set<String> ON_OFF = Set.of("enabled", "disabled");

    public GatewayStartupValidator(GpuGatewayProperties properties) {
        validate(properties);
    }

    static void validate(GpuGatewayProperties p) {
        GpuGatewayProperties.Budget budget = p.getBudget();
        require(ON_OFF.contains(budget.getEnforcement()),
                "budget.enforcement must be enabled|disabled, was '" + budget.getEnforcement() + "'");
        require(ON_OFF.contains(p.getUsage().getCostLedger()),
                "usage.cost-ledger must be enabled|disabled, was '" + p.getUsage().getCostLedger() + "'");
        if (budget.isEnabled()) {
            require(p.getUsage().isCostLedgerEnabled(),
                    "budget enforcement requires usage.cost-ledger: enabled (its persistent state)");
            require(budget.getDefaultDailyUsd() != null && budget.getDefaultDailyUsd().signum() >= 0,
                    "budget.default-daily-usd must be set and >= 0");
        }
        p.getModelCatalog().getOperations().forEach((op, models) -> models.getModels().forEach((id, info) -> {
            String providerId = info.getProvider() == null ? "" : info.getProvider().toLowerCase(Locale.ROOT);
            if ("local".equals(providerId)) {
                return;
            }
            GpuGatewayProperties.ProviderConfig provider = p.getProviders().get(providerId);
            if (provider == null || !provider.isEnabled()) {
                return; // not advertised, denied at routing (Plan §4.4)
            }
            require(provider.isUsable(), "provider '" + providerId + "' is enabled and mapped by '" + id
                    + "' but has no base-url or credentials (Plan §5.5, §29)");
            if (budget.isEnabled()) {
                require(info.getInputUsdPerMillion() != null && info.getOutputUsdPerMillion() != null,
                        "budget enforcement is enabled but external model '" + id
                                + "' has no input/output-usd-per-million price");
            }
        }));
        log.info("Startup validation passed (budget={}, cost-ledger={}, sensitivity-block-tags={})",
                budget.getEnforcement(), p.getUsage().getCostLedger(), p.getSensitivity().getBlockExternalTags());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message + " — failing closed per Deployment Plan §5.5");
        }
    }
}
