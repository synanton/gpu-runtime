package org.synanton.gpu.adapter.in.grpc;

import org.synanton.gpu.domain.model.Execution;
import org.synanton.gpu.domain.port.in.CancelUseCase;
import org.synanton.gpu.domain.port.in.ExecuteUseCase;
import org.synanton.gpu.domain.port.in.GetCapacityUseCase;
import org.synanton.gpu.domain.port.in.GetModelsUseCase;
import org.synanton.gpu.domain.port.in.GetStatusUseCase;
import org.synanton.gpu.domain.service.AdmissionService.AdmissionException;
import org.synanton.gpu.domain.service.IdempotencyService.RequestIdReuseException;
import org.synanton.gpu.domain.service.RoutingDeniedException;
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
import org.synanton.gpu.v1.GetModelsRequest;
import org.synanton.gpu.v1.GetModelsResponse;
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
    private final GetModelsUseCase getModelsUseCase;
    private final ResponseMapper responseMapper;

    @Override
    public void execute(ExecutionRequest request, StreamObserver<ExecutionResponse> observer) {
        log.info("Execute: request_id={} model={} tenant={}",
                request.getRequestId(), request.getModel(), request.getTenantId());
        try {
            Execution execution = executeUseCase.execute(request);
            observer.onNext(responseMapper.toExecutionResponse(execution));
            observer.onCompleted();
        } catch (Exception e) {
            observer.onError(toDenial(e, request.getRequestId()));
        }
    }

    /**
     * {@code ExecuteStream} (Deployment Plan §10.1): zero or more {@code data} chunks — one
     * OpenAI-compatible chunk JSON each, logical model ID — then exactly one
     * {@code terminal}. The runtime's {@code [DONE]} is consumed here: the terminal message
     * replaces it. If the client disconnects, remaining frames are dropped but the
     * execution runs to completion (same semantics as Execute, per the proto contract).
     */
    @Override
    public void executeStream(ExecutionRequest request,
                              StreamObserver<org.synanton.gpu.v1.ExecutionChunk> observer) {
        log.info("ExecuteStream: request_id={} model={} tenant={}",
                request.getRequestId(), request.getModel(), request.getTenantId());
        var serverObserver = observer instanceof io.grpc.stub.ServerCallStreamObserver<?> so ? so : null;
        String[] executionId = {""};
        ExecuteUseCase.StreamListener listener = new ExecuteUseCase.StreamListener() {
            @Override
            public void onAdmitted(String id) {
                executionId[0] = id;
            }

            @Override
            public void onChunk(byte[] frame) {
                if (serverObserver != null && serverObserver.isCancelled()) {
                    return;
                }
                byte[] data = sseData(frame);
                if (data == null) {
                    return; // [DONE] — replaced by the terminal message
                }
                observer.onNext(org.synanton.gpu.v1.ExecutionChunk.newBuilder()
                        .setRequestId(request.getRequestId())
                        .setExecutionId(executionId[0])
                        .setData(com.google.protobuf.ByteString.copyFrom(data))
                        .build());
            }
        };
        try {
            Execution execution = executeUseCase.executeStream(request, listener);
            if (serverObserver != null && serverObserver.isCancelled()) {
                return;
            }
            observer.onNext(org.synanton.gpu.v1.ExecutionChunk.newBuilder()
                    .setRequestId(execution.requestId())
                    .setExecutionId(execution.executionId())
                    .setTerminal(responseMapper.toExecutionResponse(execution))
                    .build());
            observer.onCompleted();
        } catch (Exception e) {
            observer.onError(toDenial(e, request.getRequestId()));
        }
    }

    /** SSE frame {@code data: <json>\n\n} → JSON bytes; {@code null} for {@code [DONE]}. */
    static byte[] sseData(byte[] frame) {
        String text = new String(frame, java.nio.charset.StandardCharsets.UTF_8).strip();
        if (text.startsWith("data:")) {
            text = text.substring(5).strip();
        }
        return "[DONE]".equals(text) ? null : text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Maps a pre-execution denial to a gRPC status carrying the canonical code
     * (Deployment Plan §16.2) in the {@code x-synanton-error-code} trailer and as the
     * description prefix. Shared by Execute and ExecuteStream so both RPCs report
     * identical status/code pairs.
     */
    static io.grpc.StatusRuntimeException toDenial(Exception e, String requestId) {
        Status status;
        String code;
        String message;
        if (e instanceof RoutingDeniedException rd) {
            code = rd.getCode();
            message = rd.getMessage();
            status = switch (code) {
                case "routing_disabled", "no_local_fallback", "sensitive_model_external_blocked" ->
                        Status.PERMISSION_DENIED;
                case "provider_unavailable", "budget_state_unavailable" -> Status.UNAVAILABLE;
                case "budget_exceeded" -> Status.RESOURCE_EXHAUSTED;
                case "model_not_found" -> Status.NOT_FOUND;
                default -> Status.FAILED_PRECONDITION; // capability_not_supported, provider_not_configured
            };
            log.warn("Routing denied: code={} request_id={}", code, requestId);
        } else if (e instanceof RequestIdReuseException) {
            code = "idempotency_conflict";
            message = "request_id reused with a different request";
            status = Status.INVALID_ARGUMENT;
            log.warn("RequestId reuse detected: request_id={}", requestId);
        } else if (e instanceof AdmissionException ae) {
            message = ae.getMessage();
            switch (ae.getRejection()) {
                case INVALID_ARGUMENT -> { status = Status.INVALID_ARGUMENT; code = "invalid_request"; }
                case MODEL_NOT_FOUND -> { status = Status.NOT_FOUND; code = "model_not_found"; }
                case CONCURRENCY_LIMIT -> { status = Status.RESOURCE_EXHAUSTED; code = "concurrency_limit_reached"; }
                default -> { status = Status.RESOURCE_EXHAUSTED; code = "capacity_exceeded"; }
            }
            log.warn("Admission rejected: {} request_id={}", ae.getRejection(), requestId);
        } else {
            code = "internal_error";
            message = "Internal error; check Gateway logs";
            status = Status.INTERNAL;
            log.error("Unexpected error: request_id={}", requestId, e);
        }
        return status.withDescription(code + ": " + message).asRuntimeException(errorCodeTrailers(code));
    }

    /** Trailer key carrying the canonical error code (Deployment Plan §16). */
    public static final io.grpc.Metadata.Key<String> ERROR_CODE_KEY =
            io.grpc.Metadata.Key.of("x-synanton-error-code", io.grpc.Metadata.ASCII_STRING_MARSHALLER);

    static io.grpc.Metadata errorCodeTrailers(String code) {
        io.grpc.Metadata trailers = new io.grpc.Metadata();
        trailers.put(ERROR_CODE_KEY, code);
        return trailers;
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

    @Override
    public void getModels(GetModelsRequest request, StreamObserver<GetModelsResponse> observer) {
        log.debug("GetModels: operation={} provider={} tenant={}",
                request.getOperation(), request.getProvider(), request.getTenantId());
        try {
            GetModelsResponse response = getModelsUseCase.getModels(request);
            observer.onNext(response);
            observer.onCompleted();
        } catch (Exception e) {
            log.error("Unexpected error in GetModels: operation={}", request.getOperation(), e);
            observer.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        }
    }
}
