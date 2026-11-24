package com.orynx.orchestrator.resilience.model;

import java.util.Set;

/**
 * Valid lifecycle states for a resilience failure experiment.
 * State transitions are strictly validated to enforce deterministic experiment execution.
 */
public enum ExperimentState {
    CREATED,
    VALIDATING,
    READY,
    RUNNING,
    OBSERVING,
    RECOVERING,
    COMPLETED,
    FAILED,
    ABORTED,
    TIMED_OUT,
    CLEANUP_FAILED;

    private static final Set<ExperimentState> TERMINAL_STATES = Set.of(
            COMPLETED, FAILED, ABORTED, TIMED_OUT, CLEANUP_FAILED
    );

    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }

    public boolean canTransitionTo(ExperimentState target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }
        // Terminal states cannot transition to anything
        if (this.isTerminal()) {
            return false;
        }
        // Any non-terminal state can transition to CLEANUP_FAILED if cleanup fails
        if (target == CLEANUP_FAILED) {
            return true;
        }
        // Any non-terminal state can transition to ABORTED on abort request
        if (target == ABORTED) {
            return true;
        }
        // Any active running/observing/recovering state can transition to TIMED_OUT
        if (target == TIMED_OUT) {
            return this == RUNNING || this == OBSERVING || this == RECOVERING;
        }

        return switch (this) {
            case CREATED -> target == VALIDATING;
            case VALIDATING -> target == READY || target == FAILED;
            case READY -> target == RUNNING;
            case RUNNING -> target == OBSERVING || target == FAILED;
            case OBSERVING -> target == RECOVERING || target == FAILED;
            case RECOVERING -> target == COMPLETED || target == FAILED;
            default -> false;
        };
    }
}
