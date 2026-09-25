package org.synanton.gpu.domain.service;

import com.google.protobuf.ByteString;
import org.synanton.gpu.domain.model.ModelCapabilities;
import org.synanton.gpu.domain.port.out.ExecutionRepository;
import org.synanton.gpu.domain.port.out.ModelRepository;
import org.synanton.gpu.domain.service.AdmissionService.AdmissionException;
import org.synanton.gpu.domain.service.AdmissionService.AdmissionRejection;
import org.synanton.gpu.v1.*;
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
    void shouldRejectWhenModelVersionMissing() {
        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setRequestId("req-test-1")
                .setTenantId("tenant-abc")
                .setModel("model-a")
                .setOperation(Operation.SYNTHESIZE)
                .setPayload(ByteString.copyFromUtf8("{}"))
                .build();

        assertThatThrownBy(() -> admissionService.admit(request))
                .isInstanceOf(AdmissionException.class)
                .extracting(e -> ((AdmissionException) e).getRejection())
                .isEqualTo(AdmissionRejection.INVALID_ARGUMENT);
    }

    @Test
    void shouldRejectWhenRequestIdMissing() {
        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setTenantId("tenant")
                .setModel("model-a")
                .setModelVersion("1.0")
                .setOperation(Operation.SYNTHESIZE)
                .build();

        assertThatThrownBy(() -> admissionService.admit(request))
                .isInstanceOf(AdmissionException.class)
                .extracting(e -> ((AdmissionException) e).getRejection())
                .isEqualTo(AdmissionRejection.INVALID_ARGUMENT);
    }

    @Test
    void shouldRejectOversizedRequestId() {
        // Deployment Plan §21: request_id is 1–255 characters
        ExecutionRequest request = buildRequest("model-a", 0).toBuilder()
                .setRequestId("r".repeat(256))
                .build();

        assertThatThrownBy(() -> admissionService.validateFields(request))
                .isInstanceOf(AdmissionException.class)
                .extracting(e -> ((AdmissionException) e).getRejection())
                .isEqualTo(AdmissionRejection.INVALID_ARGUMENT);
    }

    private ExecutionRequest buildRequest(String modelId, int ignoredMaxTokens) {
        return ExecutionRequest.newBuilder()
                .setRequestId("req-test-1")
                .setTenantId("tenant-abc")
                .setModel(modelId)
                .setModelVersion("1.0")
                .setOperation(Operation.SYNTHESIZE)
                .setPayload(ByteString.copyFromUtf8("{}"))
                .build();
    }
}
