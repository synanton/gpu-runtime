package org.synanton.gpu.domain.port.out;

import org.synanton.gpu.domain.model.LedgerEntry;

import java.math.BigDecimal;

/** Outbound port: GPU-7 cost ledger (T-K8S-48), the source of truth for budgets (T-K8S-48b). */
public interface CostLedger {

    /** Idempotent per execution: recording the same execution twice keeps one row. */
    void record(LedgerEntry entry);

    /** Sum of known costs for the tenant in the current UTC day. */
    BigDecimal spentToday(String tenantId);
}
