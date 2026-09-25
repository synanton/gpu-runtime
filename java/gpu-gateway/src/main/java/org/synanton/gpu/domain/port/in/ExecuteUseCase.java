package org.synanton.gpu.domain.port.in;

import org.synanton.gpu.domain.model.Execution;
import org.synanton.gpu.domain.port.out.ExecutionRuntime;
import org.synanton.gpu.v1.ExecutionRequest;

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

    /**
     * Streaming variant (Deployment Plan §10, gRPC {@code ExecuteStream}). Same idempotency,
     * routing and admission as {@link #execute}; SYNTHESIZE only. Normalized SSE frames are
     * delivered to {@code listener} as they arrive; the returned execution is the terminal
     * record. An idempotent replay returns the existing execution without frames.
     *
     * @throws org.synanton.gpu.domain.service.RoutingDeniedException with
     *         {@code capability_not_supported} for non-SYNTHESIZE operations or runtimes
     *         without streaming — never a silent fallback to unary
     */
    Execution executeStream(ExecutionRequest request, StreamListener listener);

    /** Receives stream frames; told the execution ID once the request is admitted. */
    interface StreamListener extends ExecutionRuntime.StreamChunkSink {
        default void onAdmitted(String executionId) {
        }
    }
}
