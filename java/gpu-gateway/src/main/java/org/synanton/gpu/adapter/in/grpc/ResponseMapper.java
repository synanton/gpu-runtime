package org.synanton.gpu.adapter.in.grpc;

import com.google.protobuf.ByteString;
import org.synanton.gpu.domain.model.Execution;
import org.synanton.gpu.domain.model.ExecutionError;
import org.synanton.gpu.domain.model.ExecutionUsage;
import org.synanton.gpu.v1.ErrorInfo;
import org.synanton.gpu.v1.ErrorReason;
import org.synanton.gpu.v1.ExecutionResponse;
import org.synanton.gpu.v1.ExecutionState;
import org.synanton.gpu.v1.ExecutionStatus;
import org.synanton.gpu.v1.UsageReport;
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
            builder.setUsage(mapUsage(execution, execution.usage()));
        }
        return builder.build();
    }

    ExecutionStatus toStatusResponse(Execution execution) {
        ExecutionStatus.Builder builder = ExecutionStatus.newBuilder()
                .setRequestId(execution.requestId())
                .setExecutionId(execution.executionId())
                .setState(mapState(execution.state()));

        if (execution.error() != null) {
            builder.setError(mapError(execution.error()));
        }
        if (execution.usage() != null) {
            builder.setUsage(mapUsage(execution, execution.usage()));
        }
        return builder.build();
    }

    /**
     * Domain keeps ACCEPTED / MODEL_LOADING / SUCCEEDED internally.
     * Wire states match {@code synanton.gpu.v1} so platform clients can decode them.
     */
    private ExecutionState mapState(org.synanton.gpu.domain.model.ExecutionState state) {
        return switch (state) {
            case ACCEPTED, QUEUED, MODEL_LOADING -> ExecutionState.QUEUED;
            case RUNNING -> ExecutionState.RUNNING;
            case SUCCEEDED -> ExecutionState.SUCCESS;
            case FAILED -> ExecutionState.FAILED;
            case CANCELLED -> ExecutionState.CANCELLED;
        };
    }

    private ErrorInfo mapError(ExecutionError error) {
        return ErrorInfo.newBuilder()
                .setReason(mapReason(error.code()))
                .setMessage(error.message())
                .setRetryable(error.retryable())
                .build();
    }

    private ErrorReason mapReason(String domainCode) {
        if (domainCode == null) {
            return ErrorReason.EXECUTION_FAILED;
        }
        return switch (domainCode) {
            case "INVALID_ARGUMENT" -> ErrorReason.INVALID_REQUEST;
            case "UNAUTHENTICATED" -> ErrorReason.UNAUTHORIZED;
            case "PERMISSION_DENIED" -> ErrorReason.TENANT_NOT_ALLOWED;
            case "MODEL_NOT_FOUND" -> ErrorReason.MODEL_NOT_FOUND;
            case "MODEL_NOT_READY" -> ErrorReason.MODEL_NOT_READY;
            case "MODEL_LOAD_FAILED", "MODEL_LOAD_TIMEOUT" -> ErrorReason.MODEL_LOAD_TIMEOUT;
            case "CONCURRENCY_LIMIT", "CAPACITY_EXCEEDED", "GPU_QUOTA_EXCEEDED" ->
                    ErrorReason.GPU_CAPACITY_EXCEEDED;
            case "RUNTIME_UNAVAILABLE" -> ErrorReason.GPU_UNAVAILABLE;
            case "RUNTIME_TIMEOUT", "EXECUTION_TIMEOUT" -> ErrorReason.EXECUTION_TIMEOUT;
            case "EXECUTION_CANCELLED" -> ErrorReason.EXECUTION_CANCELLED;
            default -> ErrorReason.EXECUTION_FAILED;
        };
    }

    private UsageReport mapUsage(Execution execution, ExecutionUsage usage) {
        long durationMs = (long) (usage.gpuDurationSeconds() * 1000.0);
        String outcome = switch (execution.state()) {
            case SUCCEEDED -> "success";
            case CANCELLED -> "cancelled";
            case FAILED -> "failed";
            default -> "failed";
        };
        UsageReport.Builder builder = UsageReport.newBuilder()
                .setDurationMs(durationMs)
                .setInputTokens(usage.inputTokens())
                .setOutputTokens(usage.outputTokens())
                .setOutcome(outcome)
                .setModel(execution.modelId() == null ? "" : execution.modelId());
        if (usage.runtimeClass() != null) {
            builder.setGpuType(usage.runtimeClass());
        }
        if (execution.runtimeClass() != null && usage.runtimeClass() == null) {
            builder.setGpuType(execution.runtimeClass());
        }
        return builder.build();
    }
}
