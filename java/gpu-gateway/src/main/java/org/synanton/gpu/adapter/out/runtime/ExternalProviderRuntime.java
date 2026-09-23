package org.synanton.gpu.adapter.out.runtime;

import org.synanton.gpu.domain.model.ExecutionError;
import org.synanton.gpu.domain.model.ExecutionUsage;
import org.synanton.gpu.domain.model.RetryDisposition;
import org.synanton.gpu.domain.model.RuntimeTarget;
import org.synanton.gpu.v1.ExecutionRequest;

/**
 * Outbound port for communicating with external AI providers (OpenRouter, etc.).
 *
 * <p>Domain code depends only on this interface; the external HTTP implementations live in
 * {@code adapter/out/runtime/}. Provider endpoints and credentials must not leak through.
 *
 * <p>This interface supports GPU-7 (external provider) runtime implementations.
 */
public interface ExternalProviderRuntime {

    /**
     * Submits the request to the external provider and blocks until completion or timeout.
     *
     * @return the outcome of the execution attempt
     */
    RuntimeResult execute(ExecutionRequest request, RuntimeTarget target);

    /**
     * Sends a best-effort cancellation signal to the external provider.
     * Returns the disposition of the cancellation attempt.
     */
    CancellationResult cancel(String executionId, RuntimeTarget target);

    /**
     * Pings the external provider to check if the given execution is still live.
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