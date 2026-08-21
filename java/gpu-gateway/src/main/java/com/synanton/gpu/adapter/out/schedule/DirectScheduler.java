package com.synanton.gpu.adapter.out.schedule;

import com.synanton.gpu.domain.model.ModelCapabilities;
import com.synanton.gpu.domain.model.RuntimeTarget;
import com.synanton.gpu.domain.port.out.ExecutionScheduler;
import com.synanton.gpu.v1.ExecutionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Default scheduler: returns a single configured vLLM endpoint for all executions.
 *
 * <p>This implementation knows NOTHING about Kubernetes pods, node IPs, GPU device IDs,
 * or any runtime topology. It reads only the configured endpoint URL from
 * {@link com.synanton.gpu.config.GpuGatewayProperties}.
 *
 * <p>If measurements justify it, an optional {@code EqualixScheduler} can replace this
 * by implementing {@link ExecutionScheduler} and swapping the Spring bean.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DirectScheduler implements ExecutionScheduler {

    private final String vllmEndpointUrl;

    @Override
    public RuntimeTarget schedule(ExecutionRequest request, ModelCapabilities capabilities) {
        log.debug("DirectScheduler: routing to endpoint={} runtimeClass={}",
                vllmEndpointUrl, capabilities != null ? capabilities.runtimeClass() : "unknown");
        String runtimeClass = capabilities != null ? capabilities.runtimeClass() : "vllm";
        return new RuntimeTarget(vllmEndpointUrl, runtimeClass);
    }
}
