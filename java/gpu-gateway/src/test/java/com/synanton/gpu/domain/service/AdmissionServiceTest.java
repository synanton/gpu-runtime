package com.synanton.gpu.domain.service;

import com.google.protobuf.ByteString;
import com.synanton.gpu.domain.model.ModelCapabilities;
import com.synanton.gpu.domain.port.out.ExecutionRepository;
import com.synanton.gpu.domain.port.out.ModelRepository;
import com.synanton.gpu.domain.service.AdmissionService.AdmissionException;
import com.synanton.gpu.domain.service.AdmissionService.AdmissionRejection;
import com.synanton.gpu.v1.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdmissionServiceTest {

    @Mock
    private ModelRepository modelRepository;

    @Mock
    private ExecutionRepository executionRepository;

    @InjectMocks
    private AdmissionService admissionService;

    @Test
    void shouldAdmitWhenModelAvailableAndUnderLimit() {
        ModelCapabilities capabilities = new ModelCapabilities("model-a", 8, 4096, "vllm");
        when(modelRepository.getCapabilities("model-a")).thenReturn(Optional.of(capabilities));
        when(executionRepository.countActiveExecutions("model-a")).thenReturn(3);

        ModelCapabilities result = admissionService.admit(buildRequest("model-a", 512));

        assertThat(result).isEqualTo(capabilities);
    }

    @Test
    void shouldRejectWhenConcurrencyLimitReached() {
        ModelCapabilities capabilities = new ModelCapabilities("model-a", 8, 4096, "vllm");
        when(modelRepository.getCapabilities("model-a")).thenReturn(Optional.of(capabilities));
        when(executionRepository.countActiveExecutions("model-a")).thenReturn(8);

        assertThatThrownBy(() -> admissionService.admit(buildRequest("model-a", 512)))
                .isInstanceOf(AdmissionException.class)
                .extracting(e -> ((AdmissionException) e).getRejection())
                .isEqualTo(AdmissionRejection.CONCURRENCY_LIMIT);
    }

    @Test
    void shouldRejectWhenModelNotFound() {
        when(modelRepository.getCapabilities("unknown-model")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> admissionService.admit(buildRequest("unknown-model", 0)))
                .isInstanceOf(AdmissionException.class)
                .extracting(e -> ((AdmissionException) e).getRejection())
                .isEqualTo(AdmissionRejection.MODEL_NOT_FOUND);
    }

    @Test
    void shouldRejectWhenTokenLimitExceeded() {
        ModelCapabilities capabilities = new ModelCapabilities("model-a", 8, 4096, "vllm");
        when(modelRepository.getCapabilities("model-a")).thenReturn(Optional.of(capabilities));

        assertThatThrownBy(() -> admissionService.admit(buildRequest("model-a", 9999)))
                .isInstanceOf(AdmissionException.class)
                .extracting(e -> ((AdmissionException) e).getRejection())
                .isEqualTo(AdmissionRejection.INVALID_ARGUMENT);
    }

    @Test
    void shouldRejectWhenRequestIdMissing() {
        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setTenantId("tenant")
                .setModelId("model-a")
                .setOptions(ExecutionOptions.newBuilder().setOperation(Operation.SYNTHESIZE).build())
                .build();

        assertThatThrownBy(() -> admissionService.admit(request))
                .isInstanceOf(AdmissionException.class)
                .extracting(e -> ((AdmissionException) e).getRejection())
                .isEqualTo(AdmissionRejection.INVALID_ARGUMENT);
    }

    private ExecutionRequest buildRequest(String modelId, int maxTokens) {
        return ExecutionRequest.newBuilder()
                .setRequestId("req-test-1")
                .setTenantId("tenant-abc")
                .setModelId(modelId)
                .setOptions(ExecutionOptions.newBuilder()
                        .setOperation(Operation.SYNTHESIZE)
                        .setMaxTokens(maxTokens)
                        .build())
                .setPayload(ByteString.copyFromUtf8("{}"))
                .build();
    }
}
