package com.synanton.gpu.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionStateTest {

    @Test
    void shouldIdentifyTerminalStates() {
        assertThat(ExecutionState.SUCCEEDED.isTerminal()).isTrue();
        assertThat(ExecutionState.FAILED.isTerminal()).isTrue();
        assertThat(ExecutionState.CANCELLED.isTerminal()).isTrue();

        assertThat(ExecutionState.ACCEPTED.isTerminal()).isFalse();
        assertThat(ExecutionState.QUEUED.isTerminal()).isFalse();
        assertThat(ExecutionState.MODEL_LOADING.isTerminal()).isFalse();
        assertThat(ExecutionState.RUNNING.isTerminal()).isFalse();
    }

    @Test
    void shouldAllowForwardProgressTransitions() {
        assertThat(ExecutionState.ACCEPTED.canTransitionTo(ExecutionState.QUEUED)).isTrue();
        assertThat(ExecutionState.QUEUED.canTransitionTo(ExecutionState.MODEL_LOADING)).isTrue();
        assertThat(ExecutionState.MODEL_LOADING.canTransitionTo(ExecutionState.RUNNING)).isTrue();
        assertThat(ExecutionState.RUNNING.canTransitionTo(ExecutionState.SUCCEEDED)).isTrue();
    }

    @Test
    void shouldAllowCancellationFromAnyNonTerminalState() {
        assertThat(ExecutionState.ACCEPTED.canTransitionTo(ExecutionState.CANCELLED)).isTrue();
        assertThat(ExecutionState.QUEUED.canTransitionTo(ExecutionState.CANCELLED)).isTrue();
        assertThat(ExecutionState.MODEL_LOADING.canTransitionTo(ExecutionState.CANCELLED)).isTrue();
        assertThat(ExecutionState.RUNNING.canTransitionTo(ExecutionState.CANCELLED)).isTrue();
    }

    @Test
    void shouldAllowFailureFromAnyNonTerminalState() {
        assertThat(ExecutionState.ACCEPTED.canTransitionTo(ExecutionState.FAILED)).isTrue();
        assertThat(ExecutionState.QUEUED.canTransitionTo(ExecutionState.FAILED)).isTrue();
        assertThat(ExecutionState.MODEL_LOADING.canTransitionTo(ExecutionState.FAILED)).isTrue();
        assertThat(ExecutionState.RUNNING.canTransitionTo(ExecutionState.FAILED)).isTrue();
    }

    @Test
    void shouldBlockTransitionsFromTerminalStates() {
        assertThat(ExecutionState.SUCCEEDED.canTransitionTo(ExecutionState.FAILED)).isFalse();
        assertThat(ExecutionState.FAILED.canTransitionTo(ExecutionState.RUNNING)).isFalse();
        assertThat(ExecutionState.CANCELLED.canTransitionTo(ExecutionState.SUCCEEDED)).isFalse();
    }

    @Test
    void shouldBlockBackwardTransitions() {
        assertThat(ExecutionState.RUNNING.canTransitionTo(ExecutionState.ACCEPTED)).isFalse();
        assertThat(ExecutionState.RUNNING.canTransitionTo(ExecutionState.QUEUED)).isFalse();
        assertThat(ExecutionState.MODEL_LOADING.canTransitionTo(ExecutionState.ACCEPTED)).isFalse();
    }

    @Test
    void shouldBlockSkippingStates() {
        assertThat(ExecutionState.ACCEPTED.canTransitionTo(ExecutionState.RUNNING)).isFalse();
        assertThat(ExecutionState.ACCEPTED.canTransitionTo(ExecutionState.SUCCEEDED)).isFalse();
        assertThat(ExecutionState.QUEUED.canTransitionTo(ExecutionState.SUCCEEDED)).isFalse();
    }
}
