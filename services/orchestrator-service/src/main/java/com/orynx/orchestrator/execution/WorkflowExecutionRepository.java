package com.orynx.orchestrator.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, Long> {
    List<WorkflowExecution> findByWorkflowIdOrderByStartedAtDesc(Long workflowId);

    Optional<WorkflowExecution> findFirstByWorkflowIdOrderByStartedAtDesc(Long workflowId);
}
