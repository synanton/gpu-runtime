package com.synanton.gpu.domain.model;

/** Loading state of a model in the GPU runtime. */
public enum ModelStatus {
    /** Model is present in configuration but load has not been attempted. */
    UNKNOWN,
    /** Model is actively being loaded into GPU memory. */
    LOADING,
    /** Model is loaded and ready to serve inference requests. */
    READY,
    /** Model failed to load; executions for this model will be cascaded to FAILED. */
    FAILED
}
