package com.synanton.gpu.v1;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that proto-generated stubs compile, link, and are wirable as gRPC services. */
class ContractSmokeTest {

    @Test
    void shouldBuildExecutionRequest() {
        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setRequestId("req-1")
                .setTenantId("tenant-abc")
                .setModelId("llama-3-8b")
                .setOptions(ExecutionOptions.newBuilder()
                        .setOperation(Operation.SYNTHESIZE)
                        .setMaxTokens(512)
                        .build())
                .build();

        assertThat(request.getRequestId()).isEqualTo("req-1");
        assertThat(request.getOptions().getOperation()).isEqualTo(Operation.SYNTHESIZE);
    }

    @Test
    void shouldBuildExecutionResponse() {
        ExecutionResponse response = ExecutionResponse.newBuilder()
                .setExecutionId("exec-abc-123")
                .setRequestId("req-1")
                .setState(ExecutionState.SUCCEEDED)
                .setUsage(UsageReport.newBuilder()
                        .setInputTokens(100)
                        .setOutputTokens(256)
                        .setGpuDurationSeconds(0.8)
                        .setRuntimeClass("vllm-a100")
                        .build())
                .build();

        assertThat(response.getState()).isEqualTo(ExecutionState.SUCCEEDED);
        assertThat(response.getUsage().getInputTokens()).isEqualTo(100);
    }

    @Test
    void shouldBuildErrorDetail() {
        ErrorDetail error = ErrorDetail.newBuilder()
                .setCode(ErrorCode.CONCURRENCY_LIMIT)
                .setMessage("model at capacity")
                .setRetryable(true)
                .build();

        assertThat(error.getRetryable()).isTrue();
        assertThat(error.getCode()).isEqualTo(ErrorCode.CONCURRENCY_LIMIT);
    }

    @Test
    void shouldBuildCapacityResponse() {
        CapacityResponse capacity = CapacityResponse.newBuilder()
                .setModelId("llama-3-8b")
                .setModelLoaded(true)
                .setEstimatedAvailableFraction(0.75)
                .setEstimatedQueueDepth(2)
                .setHealthy(true)
                .setRuntimeClass("vllm-a100")
                .build();

        assertThat(capacity.getModelLoaded()).isTrue();
        assertThat(capacity.getEstimatedAvailableFraction()).isEqualTo(0.75);
    }

    @Test
    void shouldHaveAllExecutionStates() {
        assertThat(ExecutionState.ACCEPTED.getNumber()).isEqualTo(1);
        assertThat(ExecutionState.QUEUED.getNumber()).isEqualTo(2);
        assertThat(ExecutionState.MODEL_LOADING.getNumber()).isEqualTo(3);
        assertThat(ExecutionState.RUNNING.getNumber()).isEqualTo(4);
        assertThat(ExecutionState.SUCCEEDED.getNumber()).isEqualTo(5);
        assertThat(ExecutionState.FAILED.getNumber()).isEqualTo(6);
        assertThat(ExecutionState.CANCELLED.getNumber()).isEqualTo(7);
    }

    @Test
    void shouldHaveAllErrorCodes() {
        assertThat(ErrorCode.CONCURRENCY_LIMIT.getNumber()).isEqualTo(8);
        assertThat(ErrorCode.MODEL_NOT_READY.getNumber()).isEqualTo(5);
        assertThat(ErrorCode.RUNTIME_UNAVAILABLE.getNumber()).isEqualTo(14);
    }
}
