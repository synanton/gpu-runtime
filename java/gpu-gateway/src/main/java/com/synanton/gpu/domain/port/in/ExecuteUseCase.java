package com.synanton.gpu.domain.port.in;

import com.synanton.gpu.domain.model.Execution;
import com.synanton.gpu.v1.ExecutionRequest;

/**
 * Inbound port for submitting a GPU execution request.
 *
 * <p>Implementations enforce transactional idempotency, admission control,
 * and the full ACCEPTED → RUNNING → terminal state progression.
 */
public interface ExecuteUseCase {

    /**
     * Submits a GPU execution request, blocking until the execution reaches a terminal state
     * or the configured deadline is exceeded.
     *
     * <p>Callers retrying a timed-out or disconnected request MUST reuse the same
     * {@code request_id}. The use case guarantees exactly-once admission.
     *
     * @param request the incoming execution request from SynAnton Core
     * @return the completed or failed execution record
     */
    Execution execute(ExecutionRequest request);
}
