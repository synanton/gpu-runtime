package com.synanton.gpu.adapter.in.grpc;

import com.google.protobuf.ByteString;
import com.synanton.gpu.domain.model.Execution;
import com.synanton.gpu.domain.model.ExecutionError;
import com.synanton.gpu.domain.model.ExecutionUsage;
import com.synanton.gpu.v1.*;
import org.springframework.stereotype.Component;

/** Maps domain {@link Execution} records to proto response messages. */
@Component
class ResponseMapper {

    ExecutionResponse toExecutionResponse(Execution execution) {
        ExecutionResponse.Builder builder = ExecutionResponse.newBuilder()
                .setRequestId(execution.requestId())
                .setExecutionId(execution.executionId())
                .setState(mapState(execution.state()));

        if (execution.result() != null) {
            builder.setResult(ByteString.copyFrom(execution.result()));
        }
        if (execution.error() != null) {
            builder.setError(mapError(execution.error()));
        }
        if (execution.usage() != null) {
            builder.setUsage(mapUsage(execution.usage()));
        }
        return builder.build();
    }

    StatusResponse toStatusResponse(Execution execution) {
        StatusResponse.Builder builder = StatusResponse.newBuilder()
                .setRequestId(execution.requestId())
                .setExecutionId(execution.executionId())
                .setState(mapState(execution.state()));

        if (execution.error() != null) {
            builder.setError(mapError(execution.error()));
        }
        if (execution.usage() != null) {
            builder.setUsage(mapUsage(execution.usage()));
        }
        return builder.build();
    }

    private ExecutionState mapState(com.synanton.gpu.domain.model.ExecutionState state) {
        return switch (state) {
            case ACCEPTED      -> ExecutionState.ACCEPTED;
            case QUEUED        -> ExecutionState.QUEUED;
            case MODEL_LOADING -> ExecutionState.MODEL_LOADING;
            case RUNNING       -> ExecutionState.RUNNING;
            case SUCCEEDED     -> ExecutionState.SUCCEEDED;
            case FAILED        -> ExecutionState.FAILED;
            case CANCELLED     -> ExecutionState.CANCELLED;
        };
    }

    private ErrorDetail mapError(ExecutionError error) {
        ErrorCode code;
        try {
            code = ErrorCode.valueOf(error.code());
        } catch (IllegalArgumentException e) {
            code = ErrorCode.INTERNAL;
        }
        return ErrorDetail.newBuilder()
                .setCode(code)
                .setMessage(error.message())
                .setRetryable(error.retryable())
                .build();
    }

    private UsageReport mapUsage(ExecutionUsage usage) {
        UsageReport.Builder builder = UsageReport.newBuilder()
                .setInputTokens(usage.inputTokens())
                .setOutputTokens(usage.outputTokens())
                .setGpuDurationSeconds(usage.gpuDurationSeconds());
        if (usage.runtimeClass() != null) {
            builder.setRuntimeClass(usage.runtimeClass());
        }
        return builder.build();
    }
}
