package com.orynx.orchestrator.execution;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, Long> {
    List<WorkflowExecution> findByWorkflowIdOrderByStartedAtDesc(Long workflowId);

    Page<WorkflowExecution> findByWorkflowId(Long workflowId, Pageable pageable);

    Optional<WorkflowExecution> findFirstByWorkflowIdOrderByStartedAtDesc(Long workflowId);
}
