package com.synanton.gpu.adapter.in.grpc;

import com.synanton.gpu.domain.port.in.GetCapacityUseCase;
import com.synanton.gpu.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * gRPC inbound adapter implementing the {@code synanton.gpu.v1.GpuCapacityService} contract.
 * Advisory only — does not reserve GPU capacity.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GpuCapacityGrpcAdapter extends GpuCapacityServiceGrpc.GpuCapacityServiceImplBase {

    private final GetCapacityUseCase getCapacityUseCase;

    @Override
    public void getCapacity(CapacityRequest request, StreamObserver<CapacityResponse> observer) {
        log.debug("GetCapacity: model_id={}", request.getModelId());
        try {
            getCapacityUseCase.getCapacity(request.getModelId())
                    .ifPresentOrElse(
                            info -> {
                                int available = Math.max(0, info.capabilities().concurrencyLimit() - info.activeExecutions());
                                double fraction = info.capabilities().concurrencyLimit() > 0
                                        ? (double) available / info.capabilities().concurrencyLimit()
                                        : 0.0;
                                observer.onNext(CapacityResponse.newBuilder()
                                        .setModelId(request.getModelId())
                                        .setModelLoaded(info.modelLoaded())
                                        .setEstimatedQueueDepth(info.activeExecutions())
                                        .setEstimatedAvailableFraction(fraction)
                                        .setHealthy(info.healthy())
                                        .setRuntimeClass(info.capabilities().runtimeClass())
                                        .build());
                                observer.onCompleted();
                            },
                            () -> observer.onError(Status.NOT_FOUND
                                    .withDescription("Model not found: " + request.getModelId())
                                    .asRuntimeException())
                    );
        } catch (Exception e) {
            log.error("Unexpected error in GetCapacity: model_id={}", request.getModelId(), e);
            observer.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        }
    }
}
