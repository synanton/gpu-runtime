package org.synanton.gpu.domain.model;

import java.math.BigDecimal;

/**
 * One cost-ledger row per successful external execution (T-K8S-48). {@code costUsd} is
 * {@code null} when authoritative usage was unavailable (never estimated as zero, §10.2).
 */
public record LedgerEntry(
        String executionId,
        String requestId,
        String tenantId,
        String providerId,
        String logicalModelId,
        long inputTokens,
        long outputTokens,
        BigDecimal costUsd) {
}
