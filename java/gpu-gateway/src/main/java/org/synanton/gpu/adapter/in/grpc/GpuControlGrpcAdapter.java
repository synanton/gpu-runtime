package org.synanton.gpu.adapter.in.grpc;

import org.synanton.gpu.adapter.out.runtime.ProviderRuntimeRegistry;
import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.model.ControlOverride;
import org.synanton.gpu.domain.port.out.ProviderHealth;
import org.synanton.gpu.domain.service.RoutingControlService;
import org.synanton.gpu.domain.service.RoutingDeniedException;
import org.synanton.gpu.v1.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

/**
 * gRPC {@code GPUControlService} (Plan §32/§33/§38, T-K8S-38): runtime kill switch and
 * provider enablement, persisted and audited. Every RPC requires the {@code admin} role.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GpuControlGrpcAdapter extends GPUControlServiceGrpc.GPUControlServiceImplBase {

    private final RoutingControlService routingControl;
    private final GpuGatewayProperties properties;
    private final ProviderHealth providerHealth;
    private final ProviderRuntimeRegistry registry;
    private final CallerAuthorization callerAuthorization;

    @Override
    public void getRoutingControl(GetRoutingControlRequest request, StreamObserver<RoutingControlState> observer) {
        respond(observer, () -> { });
    }

    @Override
    public void setExternalRouting(SetExternalRoutingRequest request, StreamObserver<RoutingControlState> observer) {
        respond(observer, () -> routingControl.setExternalRouting(request.getEnabled(), principal(), request.getReason()));
    }

    @Override
    public void setProviderEnabled(SetProviderEnabledRequest request, StreamObserver<RoutingControlState> observer) {
        respond(observer, () -> routingControl.setProviderEnabled(
                request.getProviderId(), request.getEnabled(), principal(), request.getReason()));
    }

    private static String principal() {
        String p = CallerPrincipalInterceptor.PRINCIPAL.get();
        return p != null ? p : "insecure-plaintext";
    }

    private void respond(StreamObserver<RoutingControlState> observer, Runnable change) {
        try {
            callerAuthorization.requireRole("admin");
            change.run();
            observer.onNext(snapshot());
            observer.onCompleted();
        } catch (StatusRuntimeException e) {
            observer.onError(e);
        } catch (RoutingDeniedException e) {
            Status status = switch (e.getCode()) {
                case "invalid_request" -> Status.INVALID_ARGUMENT;
                case "provider_not_configured" -> Status.NOT_FOUND;
                case "routing_state_unavailable" -> Status.UNAVAILABLE;
                default -> Status.FAILED_PRECONDITION; // config_disabled
            };
            observer.onError(CallerAuthorization.denial(status, e.getCode(), e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error in GPUControlService", e);
            observer.onError(CallerAuthorization.denial(Status.INTERNAL, "internal_error", "Internal error"));
        }
    }

    private RoutingControlState snapshot() {
        Map<String, ControlOverride> overrides = routingControl.overrides();
        ControlOverride ext = overrides.get(ControlOverride.EXTERNAL_ROUTING);
        RoutingControlState.Builder state = RoutingControlState.newBuilder()
                .setExternalRoutingEnabled(routingControl.externalRoutingEnabled())
                .setExternalRoutingConfigEnabled(properties.getRouting().isExternalEnabled())
                .setExternalRoutingUpdatedBy(ext == null ? "" : ext.updatedBy())
                .setExternalRoutingReason(ext == null ? "" : ext.reason());
        new TreeMap<>(properties.getProviders()).forEach((id, config) -> {
            ControlOverride o = overrides.get(ControlOverride.providerScope(id));
            state.addProviders(ProviderControl.newBuilder()
                    .setProviderId(id)
                    .setEnabled(routingControl.providerEnabled(id))
                    .setConfigEnabled(config.isEnabled())
                    .setUpdatedBy(o == null ? "" : o.updatedBy())
                    .setReason(o == null ? "" : o.reason())
                    .setHealthy(providerHealth.isHealthy(id))
                    .setCircuitOpen(registry.isCircuitOpen(id)));
        });
        return state.build();
    }
}
