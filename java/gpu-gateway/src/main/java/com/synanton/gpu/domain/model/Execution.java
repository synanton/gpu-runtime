package com.synanton.gpu.domain.model;

import java.time.Instant;

/**
 * Immutable domain record for a GPU execution attempt.
 * This is the authoritative view reconstructed from the {@code executions} PostgreSQL table.
 *
 * <p>Invariant: state transitions are validated by {@link ExecutionState#canTransitionTo}.
 * Adapters must never bypass this guard.
 */
public record Execution(
        String executionId,
        String requestId,
        String requestHash,
        String tenantId,
        String modelId,
        ExecutionState state,
        String runtimeClass,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        Instant leasedUntil,
        ExecutionUsage usage,
        ExecutionError error,
        byte[] result
) {

    /** Returns a copy of this execution with the given state applied.
     * Throws {@link IllegalStateException} if the transition is invalid. */
    public Execution withState(ExecutionState next) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Invalid state transition: " + state + " → " + next + " for execution " + executionId);
        }
        return new Execution(
                executionId, requestId, requestHash, tenantId, modelId,
                next, runtimeClass, createdAt, Instant.now(), expiresAt, leasedUntil,
                usage, error, result
        );
    }

    public boolean isLeaseExpired() {
        return leasedUntil != null && Instant.now().isAfter(leasedUntil);
    }
}
