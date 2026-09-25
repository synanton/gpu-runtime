package org.synanton.gpu.domain.port.in;

import org.synanton.gpu.v1.GetModelsRequest;
import org.synanton.gpu.v1.GetModelsResponse;

/**
 * Inbound port for querying available models for a specific operation and provider.
 */
public interface GetModelsUseCase {

    /**
     * Returns available models for the given operation and provider, optionally filtered by tenant.
     *
     * @param request the models request containing operation, provider, and optional tenant_id
     * @return list of available models
     */
    GetModelsResponse getModels(GetModelsRequest request);
}