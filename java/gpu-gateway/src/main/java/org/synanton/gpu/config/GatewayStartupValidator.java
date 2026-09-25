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
        GpuGatewayProperties.Security security = p.getSecurity();
        require(Set.of("mtls", "insecure-plaintext").contains(security.getMode()),
                "security.mode must be mtls|insecure-plaintext, was '" + security.getMode() + "'");
        if (security.isMtls()) {
            GpuGatewayProperties.Security.Tls tls = security.getTls();
            for (String[] f : new String[][]{{"cert-chain", tls.getCertChain()},
                    {"private-key", tls.getPrivateKey()}, {"client-ca", tls.getClientCa()}}) {
                require(f[1] != null && java.nio.file.Files.isReadable(java.nio.file.Path.of(f[1])),
                        "security.tls." + f[0] + " is not a readable file (mTLS, Plan §13.1)");
            }
            require(!security.getPrincipals().isEmpty(),
                    "security.mode=mtls requires at least one security.principals entry");
        }
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
        log.info("Startup validation passed (security={}, principals={}, budget={}, cost-ledger={}, "
                        + "sensitivity-block-tags={})",
                security.getMode(), security.getPrincipals().keySet(), budget.getEnforcement(), p.getUsage().getCostLedger(), p.getSensitivity().getBlockExternalTags());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message + " — failing closed per Deployment Plan §5.5");
        }
    }
}
