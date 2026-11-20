package com.orynx.orchestrator.workflow;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates state transitions across the workflow lifecycle.
 * Ensures terminal states are immutable and operations adhere to the explicit state machine:
 *
 * CREATED   -> RUNNING, CANCELLED
 * RUNNING   -> PAUSED, COMPLETED, FAILED, CANCELLED
 * PAUSED    -> RUNNING, CANCELLED
 * FAILED    -> RUNNING (via explicit retry)
 * COMPLETED -> (terminal - no transitions)
 * CANCELLED -> (terminal - no transitions, cancellation is idempotent)
 */
public final class WorkflowStateValidator {

    private static final Map<WorkflowStatus, Set<WorkflowStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<WorkflowStatus, Set<WorkflowStatus>> transitions = new EnumMap<>(WorkflowStatus.class);

        // CREATED: Can transition to RUNNING (start) or CANCELLED (cancel)
        transitions.put(WorkflowStatus.CREATED, EnumSet.of(WorkflowStatus.RUNNING, WorkflowStatus.CANCELLED));

        // RUNNING: Can transition to PAUSED (pause), COMPLETED (success), FAILED (failure), or CANCELLED (cancel)
        transitions.put(WorkflowStatus.RUNNING, EnumSet.of(WorkflowStatus.PAUSED, WorkflowStatus.COMPLETED, WorkflowStatus.FAILED, WorkflowStatus.CANCELLED));

        // PAUSED: Can transition to RUNNING (resume) or CANCELLED (cancel)
        transitions.put(WorkflowStatus.PAUSED, EnumSet.of(WorkflowStatus.RUNNING, WorkflowStatus.CANCELLED));

        // FAILED: Can transition to RUNNING only via an explicit retry operation
        transitions.put(WorkflowStatus.FAILED, EnumSet.of(WorkflowStatus.RUNNING));

        // COMPLETED: Terminal state - no further transitions permitted
        transitions.put(WorkflowStatus.COMPLETED, Collections.emptySet());

        // CANCELLED: Terminal state - no further transitions permitted
        transitions.put(WorkflowStatus.CANCELLED, Collections.emptySet());

        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    private WorkflowStateValidator() {}

    public static boolean isValidTransition(WorkflowStatus current, WorkflowStatus next) {
        if (current == null || next == null) {
            return false;
        }
        Set<WorkflowStatus> allowed = ALLOWED_TRANSITIONS.get(current);
        return allowed != null && allowed.contains(next);
    }

    public static void validateTransition(WorkflowStatus current, WorkflowStatus next, String operation) {
        if (!isValidTransition(current, next)) {
            throw new IllegalStateException(String.format(
                    "Invalid workflow state transition for operation '%s': cannot transition from %s to %s",
                    operation, current, next
            ));
        }
    }
}
