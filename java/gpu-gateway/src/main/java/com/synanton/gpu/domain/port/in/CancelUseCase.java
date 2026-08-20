package com.synanton.gpu.domain.port.in;

import com.synanton.gpu.domain.model.Execution;

import java.util.Optional;

/**
 * Inbound port for cancelling an in-flight GPU execution.
 * Cancellation is best-effort; it does not imply rollback of primary-platform business state.
 */
public interface CancelUseCase {

    /**
     * Attempts to cancel the execution identified by {@code executionId}.
     *
     * @param executionId the Gateway-owned execution identity
     * @return the current execution record if found, or empty if not found
     */
    Optional<Execution> cancel(String executionId);
}
