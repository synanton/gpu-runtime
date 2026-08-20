package com.synanton.gpu.domain.port.in;

import com.synanton.gpu.domain.model.Execution;

import java.util.Optional;

/**
 * Inbound port for retrieving the authoritative status of a GPU execution.
 *
 * <p>This is the primary reconciliation surface. When an Execute() call times out or the
 * caller disconnects, callers MUST invoke GetStatus to determine the true outcome.
 * Implementations apply lazy reconciliation for RUNNING executions with expired leases.
 */
public interface GetStatusUseCase {

    /**
     * Returns the current execution state, applying lease-based reconciliation if needed.
     *
     * @param executionId the Gateway-owned execution identity
     * @return the execution record, or empty if not found
     */
    Optional<Execution> getStatus(String executionId);
}
