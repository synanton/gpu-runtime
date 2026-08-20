package com.synanton.gpu.domain.model;

/** Resource usage measured during a GPU execution. Stored as JSONB in the executions table. */
public record ExecutionUsage(
        long inputTokens,
        long outputTokens,
        double gpuDurationSeconds,
        String runtimeClass
) {
    public static ExecutionUsage empty() {
        return new ExecutionUsage(0, 0, 0.0, null);
    }
}
