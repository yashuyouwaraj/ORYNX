package com.orynx.execution.coordination;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ExecutionLeaseAcquisitionResult {

    public enum Status {
        ACQUIRED,
        DUPLICATE_ACTIVE,
        DUPLICATE_TERMINAL,
        PAUSED
    }

    private final Status status;
    private final String message;
    private final WorkflowExecutionCoordination coordination;

    public static ExecutionLeaseAcquisitionResult acquired(WorkflowExecutionCoordination coordination) {
        return new ExecutionLeaseAcquisitionResult(Status.ACQUIRED, "Lease acquired successfully", coordination);
    }

    public static ExecutionLeaseAcquisitionResult duplicateActive(WorkflowExecutionCoordination coordination) {
        return new ExecutionLeaseAcquisitionResult(
                Status.DUPLICATE_ACTIVE,
                "Workflow execution is actively held by instance " + coordination.getInstanceId(),
                coordination
        );
    }

    public static ExecutionLeaseAcquisitionResult duplicateTerminal(WorkflowExecutionCoordination coordination) {
        return new ExecutionLeaseAcquisitionResult(
                Status.DUPLICATE_TERMINAL,
                "Workflow execution already reached terminal state " + coordination.getStatus(),
                coordination
        );
    }

    public static ExecutionLeaseAcquisitionResult paused(WorkflowExecutionCoordination coordination) {
        return new ExecutionLeaseAcquisitionResult(
                Status.PAUSED,
                "Workflow execution is currently paused",
                coordination
        );
    }

    public boolean isAcquired() {
        return status == Status.ACQUIRED;
    }
}
