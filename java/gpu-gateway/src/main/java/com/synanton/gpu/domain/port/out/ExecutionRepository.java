package com.synanton.gpu.domain.port.out;

import com.synanton.gpu.domain.model.Execution;
import com.synanton.gpu.domain.model.ExecutionError;
import com.synanton.gpu.domain.model.ExecutionState;
import com.synanton.gpu.domain.model.ExecutionUsage;

import java.util.Optional;

/**
 * Outbound port for persisting and retrieving GPU execution records.
 * The sole implementation uses PostgreSQL via JdbcTemplate — no ORM, no Redis, no caching.
 */
public interface ExecutionRepository {

    /**
     * Inserts a new execution record in ACCEPTED state.
     * Called inside the admission transaction after {@link #acquireModelAdmissionLock}.
     */
    void save(Execution execution);

    Optional<Execution> findByExecutionId(String executionId);

    Optional<Execution> findByRequestId(String requestId);

    /**
     * Transitions the execution to {@code nextState}.
     * Uses a predicated UPDATE to prevent overwriting terminal states.
     *
     * @return true if the update was applied (i.e. the row was in a compatible state)
     */
    boolean transitionState(String executionId, ExecutionState expectedCurrentState, ExecutionState nextState);

    /**
     * Marks an execution as SUCCEEDED with usage data and result payload.
     */
    boolean completeSuccess(String executionId, ExecutionUsage usage, byte[] result);

    /**
     * Marks an execution as FAILED or CANCELLED with error detail.
     * Predicated on {@code expectedCurrentState} to prevent overwriting terminal states.
     */
    boolean completeFailure(String executionId, ExecutionState expectedCurrentState,
                            ExecutionState terminalState, ExecutionError error);

    /**
     * Updates the {@code leased_until} timestamp for a RUNNING execution.
     * Called by the heartbeat mechanism.
     */
    void refreshLease(String executionId);

    /**
     * Counts executions for {@code modelId} that are in a non-terminal state.
     * Must be called inside a transaction that holds the model admission lock.
     */
    int countActiveExecutions(String modelId);

    /**
     * Acquires a PostgreSQL advisory transaction lock scoped to {@code modelId}.
     * Must be the first operation in the admission transaction.
     *
     * <p>Uses {@code pg_advisory_xact_lock(hashtext(modelId))} so the lock is released
     * automatically at transaction end.
     */
    void acquireModelAdmissionLock(String modelId);

    /**
     * Acquires a PostgreSQL session-level advisory lock scoped to {@code lockKey}.
     * Must be released explicitly with {@link #releaseAdvisoryLock(long)}.
     * Used by the ArtifactResolver to coordinate downloads across Gateway instances.
     */
    void acquireAdvisoryLock(long lockKey);

    /** Releases a session-level advisory lock previously acquired with {@link #acquireAdvisoryLock}. */
    void releaseAdvisoryLock(long lockKey);

    /**
     * Transitions all QUEUED and MODEL_LOADING executions for {@code modelId} to FAILED
     * in a single UPDATE. Called when model loading fails to cascade the failure atomically.
     *
     * @return the number of executions that were cascaded
     */
    int failAllQueuedForModel(String modelId, ExecutionError error);
}
