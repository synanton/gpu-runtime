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

    @Override
    public GetModelsResponse getModels(GetModelsRequest request) {
        return modelCatalogService.getModels(request);
    }
}