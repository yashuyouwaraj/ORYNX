package com.orynx.execution.coordination;

import lombok.Getter;

@Getter
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
    private final Long generation;
    private final String instanceId;
    private final boolean reclaimedExpiredLease;

    public ExecutionLeaseAcquisitionResult(
            Status status,
            String message,
            WorkflowExecutionCoordination coordination,
            Long generation,
            String instanceId
    ) {
        this(status, message, coordination, generation, instanceId, false);
    }

    public ExecutionLeaseAcquisitionResult(
            Status status,
            String message,
            WorkflowExecutionCoordination coordination,
            Long generation,
            String instanceId,
            boolean reclaimedExpiredLease
    ) {
        this.status = status;
        this.message = message;
        this.coordination = coordination;
        this.generation = generation != null ? generation : (coordination != null ? coordination.getGeneration() : null);
        this.instanceId = instanceId != null ? instanceId : (coordination != null ? coordination.getInstanceId() : null);
        this.reclaimedExpiredLease = reclaimedExpiredLease;
    }

    public ExecutionLeaseAcquisitionResult(Status status, String message, WorkflowExecutionCoordination coordination) {
        this(status, message, coordination, coordination != null ? coordination.getGeneration() : null, coordination != null ? coordination.getInstanceId() : null, false);
    }

    public static ExecutionLeaseAcquisitionResult acquired(WorkflowExecutionCoordination coordination) {
        return new ExecutionLeaseAcquisitionResult(
                Status.ACQUIRED,
                "Lease acquired successfully",
                coordination,
                coordination.getGeneration(),
                coordination.getInstanceId(),
                false
        );
    }

    public static ExecutionLeaseAcquisitionResult reclaimed(WorkflowExecutionCoordination coordination) {
        return new ExecutionLeaseAcquisitionResult(
                Status.ACQUIRED,
                "Expired lease reclaimed for crash recovery",
                coordination,
                coordination.getGeneration(),
                coordination.getInstanceId(),
                true
        );
    }

    public static ExecutionLeaseAcquisitionResult duplicateActive(WorkflowExecutionCoordination coordination) {
        return new ExecutionLeaseAcquisitionResult(
                Status.DUPLICATE_ACTIVE,
                "Workflow execution is actively held by instance " + (coordination != null ? coordination.getInstanceId() : "unknown"),
                coordination
        );
    }

    public static ExecutionLeaseAcquisitionResult duplicateTerminal(WorkflowExecutionCoordination coordination) {
        return new ExecutionLeaseAcquisitionResult(
                Status.DUPLICATE_TERMINAL,
                "Workflow execution already reached terminal state " + (coordination != null ? coordination.getStatus() : "unknown"),
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
