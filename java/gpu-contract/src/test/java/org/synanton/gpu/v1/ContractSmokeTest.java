package org.synanton.gpu.v1;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that proto-generated stubs compile, link, and are wirable as gRPC services. */
class ContractSmokeTest {

    @Test
    void shouldBuildExecutionRequest() {
        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setRequestId("req-1")
                .setTenantId("tenant-abc")
                .setModel("llama-3-8b")
                .setModelVersion("1.0")
                .setOperation(Operation.SYNTHESIZE)
                .build();

        assertThat(request.getRequestId()).isEqualTo("req-1");
        assertThat(request.getOperation()).isEqualTo(Operation.SYNTHESIZE);
        assertThat(request.getModel()).isEqualTo("llama-3-8b");
    }

    @Test
    void shouldBuildExecutionResponse() {
        ExecutionResponse response = ExecutionResponse.newBuilder()
                .setExecutionId("exec-abc-123")
                .setRequestId("req-1")
                .setState(ExecutionState.SUCCESS)
                .setUsage(UsageReport.newBuilder()
                        .setInputTokens(100)
                        .setOutputTokens(256)
                        .setDurationMs(800)
                        .setGpuType("vllm-a100")
                        .setOutcome("success")
                        .build())
                .build();

        assertThat(response.getState()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(response.getUsage().getInputTokens()).isEqualTo(100);
    }

    @Test
    void shouldBuildErrorInfo() {
        ErrorInfo error = ErrorInfo.newBuilder()
                .setReason(ErrorReason.GPU_CAPACITY_EXCEEDED)
                .setMessage("model at capacity")
                .setRetryable(true)
                .build();

        assertThat(error.getRetryable()).isTrue();
        assertThat(error.getReason()).isEqualTo(ErrorReason.GPU_CAPACITY_EXCEEDED);
    }

    @Test
    void shouldBuildCapacityResponse() {
        CapacityResponse capacity = CapacityResponse.newBuilder()
                .setModel("llama-3-8b")
                .setModelLoaded(true)
                .setEstimatedAvailableFraction(0.75)
                .setEstimatedQueueDepth(2)
                .setHealthy(true)
                .build();

        assertThat(capacity.getModelLoaded()).isTrue();
        assertThat(capacity.getEstimatedAvailableFraction()).isEqualTo(0.75);
    }

    @Test
    void shouldHavePlatformWireStates() {
        assertThat(ExecutionState.QUEUED.getNumber()).isEqualTo(1);
        assertThat(ExecutionState.RUNNING.getNumber()).isEqualTo(2);
        assertThat(ExecutionState.SUCCESS.getNumber()).isEqualTo(3);
        assertThat(ExecutionState.FAILED.getNumber()).isEqualTo(4);
        assertThat(ExecutionState.CANCELLED.getNumber()).isEqualTo(5);
        assertThat(ExecutionState.TIMEOUT.getNumber()).isEqualTo(6);
    }

    @Test
    void shouldHavePlatformErrorCatalogue() {
        assertThat(ErrorReason.MODEL_NOT_READY.getNumber()).isEqualTo(5);
        assertThat(ErrorReason.GPU_UNAVAILABLE.getNumber()).isEqualTo(7);
        assertThat(ErrorReason.GPU_CAPACITY_EXCEEDED.getNumber()).isEqualTo(8);
        assertThat(ErrorReason.EXECUTION_FAILED.getNumber()).isEqualTo(11);
    }
}
