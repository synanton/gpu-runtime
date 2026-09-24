package org.synanton.gpu.domain.model;

import org.synanton.gpu.domain.port.out.ExecutionScheduler;

/**
 * Opaque target selected by {@link ExecutionScheduler} (local path) or by
 * {@code ProviderRouter} (external path, PR #15 review §5).
 *
 * <p>Contains only what the runtime adapter needs to send the request.
 * Kubernetes pod names, node IPs, and GPU device IDs must never appear here —
 * those are topology details that must not leak to SynAnton Core.
 *
 * @param endpointUrl     provider/vLLM base URL
 * @param runtimeClass    runtime class label (local) or provider id (external)
 * @param providerModelId upstream model ID for the provider call, when it differs
 *                        from the logical model ID; {@code null} on the local path
 */
public record RuntimeTarget(String endpointUrl, String runtimeClass, String providerModelId) {

    /** Local-path constructor: the provider model ID equals the logical model ID. */
    public RuntimeTarget(String endpointUrl, String runtimeClass) {
        this(endpointUrl, runtimeClass, null);
    }
}
