package org.synanton.gpu.domain.service;

import lombok.RequiredArgsConstructor;
import org.synanton.gpu.adapter.out.runtime.ModelCatalogService;
import org.synanton.gpu.domain.port.in.GetModelsUseCase;
import org.synanton.gpu.v1.GetModelsRequest;
import org.synanton.gpu.v1.GetModelsResponse;
import org.springframework.stereotype.Service;

/**
 * Implementation of GetModelsUseCase that delegates to ModelCatalogService.
 */
@Service
@RequiredArgsConstructor
public class GetModelsService implements GetModelsUseCase {

    private final ModelCatalogService modelCatalogService;
    private final RoutingControlService routingControl;

    /**
     * Plan §4.4 with runtime control (T-K8S-38): models of a provider disabled at runtime,
     * or every external model while the runtime kill switch is off, are not advertised.
     * Unreadable control state hides external models (fail closed).
     */
    @Override
    public GetModelsResponse getModels(GetModelsRequest request) {
        GetModelsResponse all = modelCatalogService.getModels(request);
        boolean externalOn;
        try {
            externalOn = routingControl.externalRoutingEnabled();
        } catch (RoutingDeniedException e) {
            externalOn = false;
        }
        GetModelsResponse.Builder out = GetModelsResponse.newBuilder();
        for (var m : all.getModelsList()) {
            String provider = m.getProvider().toLowerCase(java.util.Locale.ROOT);
            if ("local".equals(provider)) {
                out.addModels(m);
                continue;
            }
            boolean visible;
            try {
                visible = externalOn && (routingControl.providerEnabled(provider)
                        // T-K8S-52: or servable through a runtime-enabled fallback
                        || modelCatalogService.modelInfo(m.getModelId(), request.getOperation())
                                .map(info -> info.getFallbacks().stream().anyMatch(fb -> fb.getProvider() != null
                                        && routingControl.providerEnabled(fb.getProvider().toLowerCase(java.util.Locale.ROOT))))
                                .orElse(false));
            } catch (RoutingDeniedException e) {
                visible = false;
            }
            if (visible) {
                out.addModels(m);
            }
        }
        return out.build();
    }
}