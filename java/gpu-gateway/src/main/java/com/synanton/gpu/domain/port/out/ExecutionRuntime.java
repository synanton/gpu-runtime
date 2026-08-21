package com.synanton.gpu.domain.port.out;

import com.synanton.gpu.domain.model.ExecutionError;
import com.synanton.gpu.domain.model.ExecutionUsage;
import com.synanton.gpu.domain.model.RetryDisposition;
import com.synanton.gpu.domain.model.RuntimeTarget;
import com.synanton.gpu.v1.ExecutionRequest;

/**
 * Outbound port for communicating with the GPU runtime (vLLM).
 *
 * <p>Domain code depends only on this interface; the vLLM HTTP implementation lives in
 * {@code adapter/out/runtime/}. Kubernetes, pod IPs, and GPU topology must not leak through.
 */
public interface ExecutionRuntime {

    /**
     * Submits the request to the runtime and blocks until completion or timeout.
     *
     * @return the outcome of the execution attempt
     */
    RuntimeResult execute(ExecutionRequest request, RuntimeTarget target);

    /**
     * Sends a best-effort cancellation signal to the runtime.
     * Returns the disposition of the cancellation attempt.
     */
    CancellationResult cancel(String executionId, RuntimeTarget target);

    /**
     * Pings the runtime to check if the given execution is still live.
     * Used by lazy reconciliation in GetStatus.
     */
    RuntimeStatus ping(String executionId, RuntimeTarget target);

    // ─── Result types ────────────────────────────────────────────────────────

    sealed interface RuntimeResult {
        record Success(ExecutionUsage usage, byte[] result) implements RuntimeResult {}
        record Failure(ExecutionError error, RetryDisposition disposition) implements RuntimeResult {}
    }

    sealed interface CancellationResult {
        record Accepted() implements CancellationResult {}
        record AlreadyDone() implements CancellationResult {}
        record NotFound() implements CancellationResult {}
    }

    enum RuntimeStatus {
        ALIVE,
        NOT_FOUND,
        UNAVAILABLE
    }
}
