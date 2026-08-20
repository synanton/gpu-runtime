package com.synanton.gpu.domain.model;

/**
 * Opaque target selected by {@link com.synanton.gpu.domain.port.out.ExecutionScheduler}.
 *
 * <p>Contains only what the runtime adapter needs to send the request.
 * Kubernetes pod names, node IPs, and GPU device IDs must never appear here —
 * those are topology details that must not leak to SynAnton Core.
 */
public record RuntimeTarget(String endpointUrl, String runtimeClass) {
}
