package com.orynx.orchestrator.workflow.task;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowTaskRepository extends JpaRepository<WorkflowTask, Long> {
    List<WorkflowTask> findByWorkflowIdOrderByExecutionOrder(Long workflowId);

    Page<WorkflowTask> findByWorkflowId(Long workflowId, Pageable pageable);

    Optional<WorkflowTask> findByWorkflowIdAndExecutionOrder(
            Long workflowId,
            Integer executionOrder
    );

    long countByStatus(TaskStatus status);

    @Query("SELECT AVG(t.completedAt - t.startedAt) FROM WorkflowTask t WHERE t.startedAt IS NOT NULL AND t.completedAt IS NOT NULL")
    Double findAverageCompletedDurationMs();
}
