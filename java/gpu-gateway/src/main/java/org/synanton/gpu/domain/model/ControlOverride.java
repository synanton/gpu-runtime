package org.synanton.gpu.domain.model;

import java.time.Instant;

/** A persisted runtime routing-control override (T-K8S-38). */
public record ControlOverride(String scope, boolean enabled, String updatedBy, String reason, Instant updatedAt) {

    public static final String EXTERNAL_ROUTING = "external-routing";

    public static String providerScope(String providerId) {
        return "provider:" + providerId;
    }
}
