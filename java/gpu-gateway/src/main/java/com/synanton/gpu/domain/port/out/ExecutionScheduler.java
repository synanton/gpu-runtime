package com.synanton.gpu.domain.port.out;

import com.synanton.gpu.domain.model.ModelCapabilities;
import com.synanton.gpu.domain.model.RuntimeTarget;
import com.synanton.gpu.v1.ExecutionRequest;

/**
 * Outbound port for selecting a runtime target for an execution.
 *
 * <p>Implementations must know NOTHING about: Kubernetes pod names, node IPs, GPU device IDs,
 * or any infrastructure topology. The returned {@link RuntimeTarget} contains only what the
 * runtime adapter needs to send the request.
 *
 * <p>The default implementation is {@code DirectScheduler}, which returns a single configured
 * vLLM endpoint. An optional {@code EqualixScheduler} may replace it post-GPU-4 if measurements
 * justify the added complexity.
 */
public interface ExecutionScheduler {

    /**
     * Selects a runtime target for the given request.
     *
     * @param request      the execution request
     * @param capabilities the model's configured capabilities
     * @return the selected runtime target
     */
    RuntimeTarget schedule(ExecutionRequest request, ModelCapabilities capabilities);
}
