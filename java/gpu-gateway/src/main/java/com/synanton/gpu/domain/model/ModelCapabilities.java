package com.synanton.gpu.domain.model;

/**
 * Capability descriptor for a configured model.
 * Read from configuration at startup; not stored in PostgreSQL.
 */
public record ModelCapabilities(
        String modelId,
        int concurrencyLimit,
        int maxInputTokens,
        String runtimeClass
) {
    public ModelCapabilities {
        if (concurrencyLimit < 1) {
            throw new IllegalArgumentException("concurrencyLimit must be >= 1");
        }
        if (maxInputTokens < 1) {
            throw new IllegalArgumentException("maxInputTokens must be >= 1");
        }
    }
}
