package com.orynx.orchestrator.resilience;

import com.orynx.orchestrator.resilience.model.ExperimentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentLifecycleTest {

    @Test
    @DisplayName("Standard happy-path lifecycle transitions are valid")
    void happyPathTransitions() {
        assertTrue(ExperimentState.CREATED.canTransitionTo(ExperimentState.VALIDATING));
        assertTrue(ExperimentState.VALIDATING.canTransitionTo(ExperimentState.READY));
        assertTrue(ExperimentState.READY.canTransitionTo(ExperimentState.RUNNING));
        assertTrue(ExperimentState.RUNNING.canTransitionTo(ExperimentState.OBSERVING));
        assertTrue(ExperimentState.OBSERVING.canTransitionTo(ExperimentState.RECOVERING));
        assertTrue(ExperimentState.RECOVERING.canTransitionTo(ExperimentState.COMPLETED));
    }

    @Test
    @DisplayName("Failure transitions from active states are valid")
    void failureTransitions() {
        assertTrue(ExperimentState.VALIDATING.canTransitionTo(ExperimentState.FAILED));
        assertTrue(ExperimentState.RUNNING.canTransitionTo(ExperimentState.FAILED));
        assertTrue(ExperimentState.OBSERVING.canTransitionTo(ExperimentState.FAILED));
        assertTrue(ExperimentState.RECOVERING.canTransitionTo(ExperimentState.FAILED));
    }

    @Test
    @DisplayName("Abort transitions from active states are valid")
    void abortTransitions() {
        assertTrue(ExperimentState.CREATED.canTransitionTo(ExperimentState.ABORTED));
        assertTrue(ExperimentState.VALIDATING.canTransitionTo(ExperimentState.ABORTED));
        assertTrue(ExperimentState.READY.canTransitionTo(ExperimentState.ABORTED));
        assertTrue(ExperimentState.RUNNING.canTransitionTo(ExperimentState.ABORTED));
        assertTrue(ExperimentState.OBSERVING.canTransitionTo(ExperimentState.ABORTED));
        assertTrue(ExperimentState.RECOVERING.canTransitionTo(ExperimentState.ABORTED));
    }

    @Test
    @DisplayName("Cleanup failure transition from active states is valid")
    void cleanupFailureTransitions() {
        assertTrue(ExperimentState.RUNNING.canTransitionTo(ExperimentState.CLEANUP_FAILED));
        assertTrue(ExperimentState.RECOVERING.canTransitionTo(ExperimentState.CLEANUP_FAILED));
    }

    @Test
    @DisplayName("Terminal states cannot transition to any other state")
    void terminalStatesCannotTransition() {
        assertTrue(ExperimentState.COMPLETED.isTerminal());
        assertTrue(ExperimentState.FAILED.isTerminal());
        assertTrue(ExperimentState.ABORTED.isTerminal());
        assertTrue(ExperimentState.CLEANUP_FAILED.isTerminal());

        assertFalse(ExperimentState.COMPLETED.canTransitionTo(ExperimentState.RUNNING));
        assertFalse(ExperimentState.FAILED.canTransitionTo(ExperimentState.READY));
        assertFalse(ExperimentState.ABORTED.canTransitionTo(ExperimentState.COMPLETED));
        assertFalse(ExperimentState.CLEANUP_FAILED.canTransitionTo(ExperimentState.COMPLETED));
    }

    @Test
    @DisplayName("Invalid state jumps are rejected")
    void invalidJumpsRejected() {
        assertFalse(ExperimentState.CREATED.canTransitionTo(ExperimentState.COMPLETED));
        assertFalse(ExperimentState.CREATED.canTransitionTo(ExperimentState.RUNNING));
        assertFalse(ExperimentState.READY.canTransitionTo(ExperimentState.COMPLETED));
        assertFalse(ExperimentState.VALIDATING.canTransitionTo(ExperimentState.COMPLETED));
    }
}
