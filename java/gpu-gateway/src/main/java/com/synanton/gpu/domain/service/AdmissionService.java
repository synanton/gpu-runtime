package com.synanton.gpu.domain.service;

import com.synanton.gpu.domain.model.ModelCapabilities;
import com.synanton.gpu.domain.port.out.ExecutionRepository;
import com.synanton.gpu.domain.port.out.ModelRepository;
import com.synanton.gpu.v1.ExecutionOptions;
import com.synanton.gpu.v1.ExecutionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Validates an incoming Execute request against model capabilities and current concurrency.
 *
 * <p>Admission decisions are made inside the advisory-locked transaction in
 * {@link ExecuteService} — never outside a transaction. This ensures two concurrent
 * Execute calls cannot both consume the same concurrency slot.
 */
@Component
@RequiredArgsConstructor
public class AdmissionService {

    private final ModelRepository modelRepository;
    private final ExecutionRepository executionRepository;

    /**
     * Validates the request and checks concurrency, returning model capabilities on success.
     * Must be called inside a transaction that holds the model admission lock.
     *
     * @param request the incoming execution request
     * @return the model's capabilities if admission is granted
     * @throws AdmissionException if admission is rejected for any reason
     */
    public ModelCapabilities admit(ExecutionRequest request) {
        validateFields(request);

        ModelCapabilities capabilities = modelRepository.getCapabilities(request.getModelId())
                .orElseThrow(() -> new AdmissionException(
                        AdmissionRejection.MODEL_NOT_FOUND,
                        "Model not found: " + request.getModelId()));

        validateTokenLimit(request.getOptions(), capabilities);
        checkConcurrencyLimit(request.getModelId(), capabilities);

        return capabilities;
    }

    private void validateFields(ExecutionRequest request) {
        if (request.getRequestId().isBlank()) {
            throw new AdmissionException(AdmissionRejection.INVALID_ARGUMENT, "request_id is required");
        }
        if (request.getTenantId().isBlank()) {
            throw new AdmissionException(AdmissionRejection.INVALID_ARGUMENT, "tenant_id is required");
        }
        if (request.getModelId().isBlank()) {
            throw new AdmissionException(AdmissionRejection.INVALID_ARGUMENT, "model_id is required");
        }
        if (request.getOptions().getOperation().getNumber() == 0) {
            throw new AdmissionException(AdmissionRejection.INVALID_ARGUMENT,
                    "operation must not be OPERATION_UNSPECIFIED");
        }
    }

    private void validateTokenLimit(ExecutionOptions options, ModelCapabilities capabilities) {
        if (options.getMaxTokens() > 0 && options.getMaxTokens() > capabilities.maxInputTokens()) {
            throw new AdmissionException(AdmissionRejection.INVALID_ARGUMENT,
                    "max_tokens " + options.getMaxTokens() + " exceeds model limit " + capabilities.maxInputTokens());
        }
    }

    private void checkConcurrencyLimit(String modelId, ModelCapabilities capabilities) {
        int activeCount = executionRepository.countActiveExecutions(modelId);
        if (activeCount >= capabilities.concurrencyLimit()) {
            throw new AdmissionException(AdmissionRejection.CONCURRENCY_LIMIT,
                    "Model " + modelId + " is at concurrency limit "
                            + capabilities.concurrencyLimit() + " (active: " + activeCount + ")");
        }
    }

    /** Reason codes for admission rejection, used to map to gRPC error codes. */
    public enum AdmissionRejection {
        INVALID_ARGUMENT,
        MODEL_NOT_FOUND,
        CONCURRENCY_LIMIT,
        GPU_QUOTA_EXCEEDED,
        CAPACITY_EXCEEDED
    }

    @SuppressWarnings("serial")
    public static class AdmissionException extends RuntimeException {
        private final AdmissionRejection rejection;

        public AdmissionException(AdmissionRejection rejection, String message) {
            super(message);
            this.rejection = rejection;
        }

        public AdmissionRejection getRejection() {
            return rejection;
        }
    }
}
