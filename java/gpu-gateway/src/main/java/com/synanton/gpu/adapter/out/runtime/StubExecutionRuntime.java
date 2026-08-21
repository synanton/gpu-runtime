package com.synanton.gpu.adapter.out.runtime;

import com.synanton.gpu.domain.model.ExecutionError;
import com.synanton.gpu.domain.model.RetryDisposition;
import com.synanton.gpu.domain.model.RuntimeTarget;
import com.synanton.gpu.domain.port.out.ExecutionRuntime;
import com.synanton.gpu.v1.ExecutionRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-process runtime used when {@code gpu-gateway.dispatch.strategy=stub}.
 * Does not call vLLM; Execute completes as FAILED / RUNTIME_UNAVAILABLE.
 */
@Component
@ConditionalOnProperty(name = "gpu-gateway.dispatch.strategy", havingValue = "stub", matchIfMissing = true)
public class StubExecutionRuntime implements ExecutionRuntime {

    @Override
    public RuntimeResult execute(ExecutionRequest request, RuntimeTarget target) {
        return new RuntimeResult.Failure(
                ExecutionError.nonRetryable("RUNTIME_UNAVAILABLE", "Stub runtime does not execute"),
                RetryDisposition.DEFINITELY_FAILED
        );
    }

    @Override
    public CancellationResult cancel(String executionId, RuntimeTarget target) {
        return new CancellationResult.NotFound();
    }

    @Override
    public RuntimeStatus ping(String executionId, RuntimeTarget target) {
        return RuntimeStatus.UNAVAILABLE;
    }
}
