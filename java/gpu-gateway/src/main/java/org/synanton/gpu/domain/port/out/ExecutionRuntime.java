package org.synanton.gpu.domain.port.out;

import org.synanton.gpu.domain.model.ExecutionError;
import org.synanton.gpu.domain.model.ExecutionUsage;
import org.synanton.gpu.domain.model.RetryDisposition;
import org.synanton.gpu.domain.model.RuntimeTarget;
import org.synanton.gpu.v1.ExecutionRequest;

/**
 * Outbound port for communicating with the GPU runtime (vLLM or external providers).
 *
 * <p>Domain code depends only on this interface; the specific runtime implementations live in
 * {@code adapter/out/runtime/}. Kubernetes, pod IPs, and GPU topology must not leak through.
 *
 * <p>This interface supports both GPU-5 (local vLLM) and GPU-7 (external provider) runtime implementations.
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

    // ─── Streaming (PR #15 P1.2: unary and streaming are distinct operations) ─

    /** Whether {@link #executeStreaming} is implemented. Checked before admission. */
    default boolean supportsStreaming() {
        return false;
    }

    /**
     * Streaming execution: each normalized SSE frame ({@code data: <chunk>\n\n}, logical
     * model ID restored) is delivered to {@code sink} as it arrives; {@code data: [DONE]}
     * exactly once at the end. Returns the terminal result (authoritative usage, empty
     * result body). Runtimes without streaming throw {@link UnsupportedOperationException};
     * callers must check {@link #supportsStreaming()} first and never fall back to unary.
     */
    default RuntimeResult executeStreaming(ExecutionRequest request, RuntimeTarget target,
                                           StreamChunkSink sink) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support streaming execution");
    }

    /** Downstream consumer of normalized SSE frames. */
    @FunctionalInterface
    interface StreamChunkSink {
        void onChunk(byte[] sseFrame);
    }

    // ─── Result types ────────────────────────────────────────────────────────

    sealed interface RuntimeResult {
        /** {@code upstreamRequestId}: the external provider's request ID (§35); null locally. */
        record Success(ExecutionUsage usage, byte[] result, String upstreamRequestId) implements RuntimeResult {
            public Success(ExecutionUsage usage, byte[] result) {
                this(usage, result, null);
            }
        }
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
