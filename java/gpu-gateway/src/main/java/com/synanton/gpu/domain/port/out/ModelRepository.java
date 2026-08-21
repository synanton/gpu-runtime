package com.synanton.gpu.domain.port.out;

import com.synanton.gpu.domain.model.ModelCapabilities;

import java.util.Optional;

/**
 * Outbound port for retrieving model capability configuration.
 * The implementation reads from application configuration — not from PostgreSQL.
 */
public interface ModelRepository {

    /** Returns capabilities for the given model, or empty if the model is not configured. */
    Optional<ModelCapabilities> getCapabilities(String modelId);
}
