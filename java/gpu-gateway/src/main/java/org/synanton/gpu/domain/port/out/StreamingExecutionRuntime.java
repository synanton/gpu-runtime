package org.synanton.gpu.domain.port.out;

import org.synanton.gpu.domain.model.RuntimeTarget;
import org.synanton.gpu.v1.ExecutionRequest;

/**
 * Streaming extension of {@link ExecutionRuntime} (PR #15 review P1.2: unary and
 * streaming execution are distinct in the execution abstraction).
 *
 * <p>Frames delivered to the sink are complete SSE frames ({@code data: ...\n\n}),
 * already normalized for the downstream contract (logical model ID restored —
 * the provider model ID never leaks downstream). The provider stream's
 * {@code data: [DONE]} is forwarded exactly once.
 */
public interface StreamingExecutionRuntime extends ExecutionRuntime {

    /**
     * Executes the request against the provider in streaming mode, delivering each
     * normalized SSE frame to {@code sink} as it arrives.
     *
     * <p>The returned terminal {@link RuntimeResult} carries authoritative usage
     * (from the terminal usage chunk when present) with an empty result body —
     * the streamed content lives in the frames, not in the result.
     *
     * <p>Failure before the first frame behaves exactly like a unary failure
     * (no frames emitted). Failure mid-stream is reported as
     * {@code COMPLETED_UNKNOWN} after the frames already delivered.
     */
    RuntimeResult executeStreaming(ExecutionRequest request, RuntimeTarget target, StreamChunkSink sink);

    /** Downstream consumer of normalized SSE frames. */
    @FunctionalInterface
    interface StreamChunkSink {
        void onChunk(byte[] sseFrame);
    }
}
