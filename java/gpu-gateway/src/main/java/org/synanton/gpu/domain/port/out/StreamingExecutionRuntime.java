package org.synanton.gpu.domain.port.out;

import org.synanton.gpu.domain.model.RuntimeTarget;
import org.synanton.gpu.v1.ExecutionRequest;

/**
 * Marker for runtimes that implement streaming ({@link ExecutionRuntime#executeStreaming}
 * is abstract here and {@link #supportsStreaming()} is true). Implemented by
 * {@code OpenAiProviderRuntime} (GPU-7) and {@code VllmRuntime} (GPU-5).
 */
public interface StreamingExecutionRuntime extends ExecutionRuntime {

    @Override
    default boolean supportsStreaming() {
        return true;
    }

    @Override
    RuntimeResult executeStreaming(ExecutionRequest request, RuntimeTarget target, StreamChunkSink sink);
}
