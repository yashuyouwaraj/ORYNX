package com.orynx.orchestrator.coordination.admission;

/**
 * Distributed workflow admission control interface.
 * Coordinates execution capacity across multiple orchestrator instances using Redis.
 */
public interface WorkflowAdmissionController {

    /**
     * Attempts to atomically admit a workflow for execution under configured cluster
     * and per-user limits.
     *
     * @param workflowId the ID of the workflow requesting execution
     * @param userId the authenticated user owning the workflow
     * @return the AdmissionDecision result
     * @throws WorkflowAdmissionException if rejected under capacity limits or Redis failure under FAIL_CLOSED
     */
    AdmissionDecision tryAdmit(Long workflowId, Long userId);

    /**
     * Releases an admitted workflow slot upon reaching terminal status (COMPLETED, FAILED, CANCELLED)
     * or non-running state (PAUSED).
     *
     * @param workflowId the ID of the workflow releasing execution
     * @param userId the authenticated user owning the workflow
     */
    void release(Long workflowId, Long userId);

    /**
     * Checks if a workflow is currently actively admitted in Redis.
     */
    boolean isAdmitted(Long workflowId);

    /**
     * Retrieves the current count of active admitted workflows across the cluster.
     */
    long getActiveGlobalCount();

    /**
     * Retrieves the current count of active admitted workflows for a specific user.
     */
    long getActiveUserCount(Long userId);
}
