package com.synanton.gpu.adapter.in.grpc;

import com.synanton.gpu.domain.model.Execution;
import com.synanton.gpu.domain.port.in.CancelUseCase;
import com.synanton.gpu.domain.port.in.ExecuteUseCase;
import com.synanton.gpu.domain.port.in.GetCapacityUseCase;
import com.synanton.gpu.domain.port.in.GetStatusUseCase;
import com.synanton.gpu.domain.service.AdmissionService.AdmissionException;
import com.synanton.gpu.domain.service.IdempotencyService.RequestIdReuseException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.synanton.gpu.v1.CancelRequest;
import org.synanton.gpu.v1.CancelResponse;
import org.synanton.gpu.v1.CancellationOutcome;
import org.synanton.gpu.v1.CapacityResponse;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.ExecutionResponse;
import org.synanton.gpu.v1.ExecutionStatus;
import org.synanton.gpu.v1.GPUExecutionServiceGrpc;
import org.synanton.gpu.v1.GetCapacityRequest;
import org.synanton.gpu.v1.GetStatusRequest;

import java.util.Optional;

/**
 * gRPC inbound adapter implementing {@code synanton.gpu.v1.GPUExecutionService}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GpuExecutionGrpcAdapter extends GPUExecutionServiceGrpc.GPUExecutionServiceImplBase {

    private final ExecuteUseCase executeUseCase;
    private final CancelUseCase cancelUseCase;
    private final GetStatusUseCase getStatusUseCase;
    private final GetCapacityUseCase getCapacityUseCase;
    private final ResponseMapper responseMapper;

    @Override
    public void execute(ExecutionRequest request, StreamObserver<ExecutionResponse> observer) {
        log.info("Execute: request_id={} model={} tenant={}",
                request.getRequestId(), request.getModel(), request.getTenantId());
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
                case INVALID_ARGUMENT -> Status.INVALID_ARGUMENT.withDescription(e.getMessage());
                case MODEL_NOT_FOUND -> Status.NOT_FOUND.withDescription(e.getMessage());
                case CONCURRENCY_LIMIT,
                     CAPACITY_EXCEEDED,
                     GPU_QUOTA_EXCEEDED -> Status.RESOURCE_EXHAUSTED.withDescription(e.getMessage());
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
                            ? CancellationOutcome.COMPLETED
                            : CancellationOutcome.ACCEPTED)
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
    public void getStatus(GetStatusRequest request, StreamObserver<ExecutionStatus> observer) {
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

    @Override
    public void getCapacity(GetCapacityRequest request, StreamObserver<CapacityResponse> observer) {
        log.debug("GetCapacity: model={}", request.getModel());
        try {
            getCapacityUseCase.getCapacity(request.getModel())
                    .ifPresentOrElse(
                            info -> {
                                int available = Math.max(0,
                                        info.capabilities().concurrencyLimit() - info.activeExecutions());
                                double fraction = info.capabilities().concurrencyLimit() > 0
                                        ? (double) available / info.capabilities().concurrencyLimit()
                                        : 0.0;
                                observer.onNext(CapacityResponse.newBuilder()
                                        .setModel(request.getModel())
                                        .setModelLoaded(info.modelLoaded())
                                        .setEstimatedQueueDepth(info.activeExecutions())
                                        .setEstimatedAvailableFraction(fraction)
                                        .setHealthy(info.healthy())
                                        .build());
                                observer.onCompleted();
                            },
                            () -> observer.onError(Status.NOT_FOUND
                                    .withDescription("Model not found: " + request.getModel())
                                    .asRuntimeException())
                    );
        } catch (Exception e) {
            log.error("Unexpected error in GetCapacity: model={}", request.getModel(), e);
            observer.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        }
    }
}
