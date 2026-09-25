package org.synanton.gpu.domain.port.out;

import org.synanton.gpu.domain.model.ControlOverride;

import java.util.Map;

/** Outbound port: persisted routing-control overrides shared by all Gateway replicas. */
public interface RoutingControlStore {

    /** All overrides by scope. Throws on storage failure (callers fail closed). */
    Map<String, ControlOverride> all();

    /** Upserts the override and appends to the audit trail, atomically. */
    void set(String scope, boolean enabled, String updatedBy, String reason);
}
