package org.synanton.gpu.adapter.out.database;

import org.synanton.gpu.domain.model.LedgerEntry;
import org.synanton.gpu.domain.port.out.CostLedger;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** PostgreSQL cost ledger (V2__cost_ledger.sql). */
@Repository
@RequiredArgsConstructor
public class JdbcCostLedger implements CostLedger {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void record(LedgerEntry e) {
        jdbcTemplate.update("""
                INSERT INTO cost_ledger (execution_id, request_id, tenant_id, provider_id,
                                         logical_model_id, input_tokens, output_tokens, cost_usd)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (execution_id) DO NOTHING
                """,
                e.executionId(), e.requestId(), e.tenantId(), e.providerId(),
                e.logicalModelId(), e.inputTokens(), e.outputTokens(), e.costUsd());
    }

    @Override
    public BigDecimal spentToday(String tenantId) {
        Timestamp startOfDayUtc = Timestamp.from(LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC));
        BigDecimal spent = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(cost_usd), 0)
                  FROM cost_ledger
                 WHERE tenant_id = ? AND created_at >= ?
                """, BigDecimal.class, tenantId, startOfDayUtc);
        return spent == null ? BigDecimal.ZERO : spent;
    }
}
