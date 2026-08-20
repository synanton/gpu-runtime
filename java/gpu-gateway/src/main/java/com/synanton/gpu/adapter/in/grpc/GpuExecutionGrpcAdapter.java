package com.synanton.gpu.adapter.in.grpc;

import com.synanton.gpu.domain.model.Execution;
import com.synanton.gpu.domain.port.in.*;
import com.synanton.gpu.domain.service.AdmissionService.AdmissionException;
import com.synanton.gpu.domain.service.IdempotencyService.RequestIdReuseException;
import com.synanton.gpu.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * gRPC inbound adapter implementing the {@code synanton.gpu.v1.GpuExecutionService} contract.
 *
 * <p>This adapter delegates entirely to use-case interfaces; it contains NO business logic.
 * Proto ↔ domain mapping is handled by {@link ResponseMapper}.
 * Privacy rule: request payload, tenant assertions, and token contents are never logged.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GpuExecutionGrpcAdapter extends GpuExecutionServiceGrpc.GpuExecutionServiceImplBase {

    private final ExecuteUseCase executeUseCase;
    private final CancelUseCase cancelUseCase;
    private final GetStatusUseCase getStatusUseCase;
    private final ResponseMapper responseMapper;

    @Override
    public void execute(ExecutionRequest request, StreamObserver<ExecutionResponse> observer) {
        log.info("Execute: request_id={} model={} tenant={}",
                request.getRequestId(), request.getModelId(), request.getTenantId());
        try {
            Execution execution = executeUseCase.execute(request);
            observer.onNext(responseMapper.toExecutionResponse(execution));
            observer.onCompleted();
        } catch (RequestIdReuseException e) {
            log.warn("RequestId reuse detected: request_id={}", e.getRequestId());
            observer.onError(Status.INVALID_ARGUMENT
                    .withDescription("request_id reused with different payload")
                    .asRuntimeException());
        } catch (AdmissionException e) {
            Status grpcStatus = switch (e.getRejection()) {
                case INVALID_ARGUMENT  -> Status.INVALID_ARGUMENT.withDescription(e.getMessage());
                case MODEL_NOT_FOUND   -> Status.NOT_FOUND.withDescription(e.getMessage());
                case CONCURRENCY_LIMIT,
                     CAPACITY_EXCEEDED -> Status.RESOURCE_EXHAUSTED.withDescription(e.getMessage());
                case GPU_QUOTA_EXCEEDED -> Status.RESOURCE_EXHAUSTED.withDescription(e.getMessage());
            };
            log.warn("Admission rejected: {} request_id={}", e.getRejection(), request.getRequestId());
            observer.onError(grpcStatus.asRuntimeException());
        } catch (Exception e) {
            log.error("Unexpected error in Execute: request_id={}", request.getRequestId(), e);
            observer.onError(Status.INTERNAL
                    .withDescription("Internal error; check Gateway logs")
                    .asRuntimeException());
        }
    }

    @Override
    public void cancel(CancelRequest request, StreamObserver<CancelResponse> observer) {
        if (request.getExecutionId().isBlank()) {
            observer.onError(Status.INVALID_ARGUMENT
                    .withDescription("execution_id is required").asRuntimeException());
            return;
        }
        log.info("Cancel: execution_id={}", request.getExecutionId());
        try {
            Optional<Execution> result = cancelUseCase.cancel(request.getExecutionId());
            CancellationOutcome outcome = result
                    .map(exec -> exec.state().isTerminal()
                            ? CancellationOutcome.ALREADY_COMPLETED
                            : CancellationOutcome.CANCEL_ACCEPTED)
                    .orElse(CancellationOutcome.NOT_APPLICABLE);

            observer.onNext(CancelResponse.newBuilder()
                    .setExecutionId(request.getExecutionId())
                    .setOutcome(outcome)
                    .build());
            observer.onCompleted();
        } catch (Exception e) {
            log.error("Unexpected error in Cancel: execution_id={}", request.getExecutionId(), e);
            observer.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        }
    }

    @Override
    public void getStatus(StatusRequest request, StreamObserver<StatusResponse> observer) {
        if (request.getExecutionId().isBlank()) {
            observer.onError(Status.INVALID_ARGUMENT
                    .withDescription("execution_id is required").asRuntimeException());
            return;
        }
        log.debug("GetStatus: execution_id={}", request.getExecutionId());
        try {
            Optional<Execution> result = getStatusUseCase.getStatus(request.getExecutionId());
            if (result.isEmpty()) {
                observer.onError(Status.NOT_FOUND
                        .withDescription("execution_id not found: " + request.getExecutionId())
                        .asRuntimeException());
                return;
            }
            observer.onNext(responseMapper.toStatusResponse(result.get()));
            observer.onCompleted();
        } catch (Exception e) {
            log.error("Unexpected error in GetStatus: execution_id={}", request.getExecutionId(), e);
            observer.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        }
    }
}
