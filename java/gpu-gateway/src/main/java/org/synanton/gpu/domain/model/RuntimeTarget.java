package org.synanton.gpu.domain.model;

import org.synanton.gpu.domain.port.out.ExecutionScheduler;

/**
 * Opaque target selected by {@link ExecutionScheduler}.
 *
 * <p>Contains only what the runtime adapter needs to send the request.
 * Kubernetes pod names, node IPs, and GPU device IDs must never appear here —
 * those are topology details that must not leak to SynAnton Core.
 */
public record RuntimeTarget(String endpointUrl, String runtimeClass) {
}
