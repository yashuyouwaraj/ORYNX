package com.orynx.orchestrator.workflow.task;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    @Query("SELECT COUNT(t) FROM WorkflowTask t WHERE t.workflow.userId = :userId")
    long countByWorkflowUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(t) FROM WorkflowTask t WHERE t.workflow.userId = :userId AND t.status = :status")
    long countByWorkflowUserIdAndStatus(@Param("userId") Long userId, @Param("status") TaskStatus status);

    @Query("SELECT AVG(t.completedAt - t.startedAt) FROM WorkflowTask t WHERE t.workflow.userId = :userId AND t.startedAt IS NOT NULL AND t.completedAt IS NOT NULL")
    Double findAverageCompletedDurationMsByUserId(@Param("userId") Long userId);
}
