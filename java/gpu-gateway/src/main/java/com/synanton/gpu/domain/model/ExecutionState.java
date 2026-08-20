package com.synanton.gpu.domain.model;

import java.util.Set;

/**
 * Lifecycle states of a single GPU execution attempt.
 *
 * <p>Valid forward transitions:
 * <pre>
 *   ACCEPTED → QUEUED → MODEL_LOADING → RUNNING → SUCCEEDED
 *   Any non-terminal state can transition to → FAILED | CANCELLED
 * </pre>
 *
 * <p>Invariant: once a terminal state is reached (SUCCEEDED, FAILED, CANCELLED),
 * no further transitions are permitted.
 */
public enum ExecutionState {

    ACCEPTED,
    QUEUED,
    MODEL_LOADING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    private static final Set<ExecutionState> TERMINAL = Set.of(SUCCEEDED, FAILED, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Returns true if transitioning from this state to {@code next} is valid. */
    public boolean canTransitionTo(ExecutionState next) {
        if (isTerminal()) {
            return false;
        }
        return switch (this) {
            case ACCEPTED      -> next == QUEUED        || next == FAILED || next == CANCELLED;
            case QUEUED        -> next == MODEL_LOADING || next == FAILED || next == CANCELLED;
            case MODEL_LOADING -> next == RUNNING       || next == FAILED || next == CANCELLED;
            case RUNNING       -> next == SUCCEEDED     || next == FAILED || next == CANCELLED;
            default            -> false;
        };
    }

    public static boolean isActive(ExecutionState state) {
        return !state.isTerminal();
    }
}
