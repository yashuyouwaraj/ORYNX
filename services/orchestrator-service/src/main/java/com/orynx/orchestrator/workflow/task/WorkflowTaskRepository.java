package com.orynx.orchestrator.workflow.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowTaskRepository extends JpaRepository<WorkflowTask, Long> {
    List<WorkflowTask> findByWorkflowIdOrderByExecutionOrder(Long workflowId);

    Optional<WorkflowTask> findByWorkflowIdAndExecutionOrder(
            Long workflowId,
            Integer executionOrder
    );
}
