package com.synanton.gpu.domain.port.out;

import com.synanton.gpu.domain.model.ModelStatus;

/**
 * Outbound port for managing model lifecycle in the GPU runtime.
 *
 * <p>In GPU-3 the implementation queries the vLLM runtime for model status
 * and blocks (with timeout) until the model is ready to serve.
 */
public interface ModelManager {

    /** Returns the current loading status of the model in the GPU runtime. */
    ModelStatus getStatus(String modelId);

    /**
     * Ensures the model is loaded and ready to serve.
     * Blocks until ready or timeout; transitions the caller's execution to MODEL_LOADING state
     * if the model is not yet available.
     *
     * @throws ModelLoadException if the model fails to load within the configured timeout
     */
    void ensureReady(String modelId);

    /** Thrown when a model fails to load or times out during loading. */
    @SuppressWarnings("serial")
    class ModelLoadException extends RuntimeException {
        private final String modelId;

        public ModelLoadException(String modelId, String message) {
            super(message);
            this.modelId = modelId;
        }

        public ModelLoadException(String modelId, String message, Throwable cause) {
            super(message, cause);
            this.modelId = modelId;
        }

        public String getModelId() {
            return modelId;
        }
    }
}
