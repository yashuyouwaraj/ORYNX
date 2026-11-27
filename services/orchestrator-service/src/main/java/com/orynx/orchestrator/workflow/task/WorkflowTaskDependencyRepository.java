package com.orynx.orchestrator.workflow.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowTaskDependencyRepository extends JpaRepository<WorkflowTaskDependency, Long> {
    List<WorkflowTaskDependency> findByWorkflowId(Long workflowId);
}
