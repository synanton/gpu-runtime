package org.synanton.gpu.adapter.in.grpc;

import org.synanton.gpu.domain.service.AdmissionService.AdmissionException;
import org.synanton.gpu.domain.service.AdmissionService.AdmissionRejection;
import org.synanton.gpu.domain.service.IdempotencyService.RequestIdReuseException;
import org.synanton.gpu.domain.service.RoutingDeniedException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Deployment Plan §16.2: every pre-execution denial carries status + canonical code trailer. */
class DenialMappingTest {

    private static String trailerCode(StatusRuntimeException e) {
        return e.getTrailers().get(GpuExecutionGrpcAdapter.ERROR_CODE_KEY);
    }

    @ParameterizedTest
    @CsvSource({
            "routing_disabled, PERMISSION_DENIED",
            "no_local_fallback, PERMISSION_DENIED",
            "sensitive_model_external_blocked, PERMISSION_DENIED",
            "provider_unavailable, UNAVAILABLE",
            "budget_state_unavailable, UNAVAILABLE",
            "budget_exceeded, RESOURCE_EXHAUSTED",
            "model_not_found, NOT_FOUND",
            "capability_not_supported, FAILED_PRECONDITION",
            "provider_not_configured, FAILED_PRECONDITION",
    })
    void routingDenialsMapToCanonicalStatusAndCode(String code, Status.Code expected) {
        StatusRuntimeException e = GpuExecutionGrpcAdapter.toDenial(
                new RoutingDeniedException(code, "why"), "req-1");

        assertThat(e.getStatus().getCode()).isEqualTo(expected);
        assertThat(trailerCode(e)).isEqualTo(code);
        assertThat(e.getStatus().getDescription()).startsWith(code + ": ");
    }

    @Test
    void idempotencyConflict() {
        StatusRuntimeException e = GpuExecutionGrpcAdapter.toDenial(
                new RequestIdReuseException("req-1", "h1", "h2"), "req-1");
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(trailerCode(e)).isEqualTo("idempotency_conflict");
    }

    @Test
    void admissionRejections() {
        assertThat(trailerCode(GpuExecutionGrpcAdapter.toDenial(
                new AdmissionException(AdmissionRejection.INVALID_ARGUMENT, "x"), "r")))
                .isEqualTo("invalid_request");
        assertThat(trailerCode(GpuExecutionGrpcAdapter.toDenial(
                new AdmissionException(AdmissionRejection.CONCURRENCY_LIMIT, "x"), "r")))
                .isEqualTo("concurrency_limit_reached");
        assertThat(trailerCode(GpuExecutionGrpcAdapter.toDenial(
                new AdmissionException(AdmissionRejection.MODEL_NOT_FOUND, "x"), "r")))
                .isEqualTo("model_not_found");
    }

    @Test
    void unexpectedFailureIsInternalWithoutLeakingDetail() {
        StatusRuntimeException e = GpuExecutionGrpcAdapter.toDenial(
                new IllegalStateException("secret-ish detail"), "r");
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(trailerCode(e)).isEqualTo("internal_error");
        assertThat(e.getStatus().getDescription()).doesNotContain("secret-ish");
    }

    @Test
    void errorInfoCodeIsCanonicalLowerCase() {
        assertThat(ResponseMapper.canonicalCode("MODEL_LOAD_FAILED")).isEqualTo("model_load_failed");
        assertThat(ResponseMapper.canonicalCode("upstream_provider_error")).isEqualTo("upstream_provider_error");
        assertThat(ResponseMapper.canonicalCode(null)).isEqualTo("internal_error");
    }
}
