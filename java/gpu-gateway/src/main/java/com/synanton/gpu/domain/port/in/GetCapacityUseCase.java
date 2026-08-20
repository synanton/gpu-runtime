package com.synanton.gpu.domain.port.in;

import com.synanton.gpu.domain.model.ModelCapabilities;

import java.util.Optional;

/**
 * Inbound port for querying advisory GPU capacity for a model.
 * A successful response does NOT reserve capacity; admission is authoritative at Execute time.
 */
public interface GetCapacityUseCase {

    /**
     * Returns advisory capacity information for the given model.
     *
     * @param modelId the model identifier
     * @return model capabilities and current advisory capacity, or empty if model is unknown
     */
    Optional<CapacityInfo> getCapacity(String modelId);

    /** Advisory capacity snapshot returned to the caller. */
    record CapacityInfo(
            ModelCapabilities capabilities,
            int activeExecutions,
            boolean modelLoaded,
            boolean healthy
    ) {}
}
