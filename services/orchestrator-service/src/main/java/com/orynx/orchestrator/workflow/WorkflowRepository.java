package com.orynx.orchestrator.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, Long> {

    List<Workflow> findAllByOrderByIdDesc();

    List<Workflow> findByDependsOnWorkflowId(Long workflowId);

    List<Workflow> findByStatusOrderByIdDesc(WorkflowStatus status);

    long countByStatus(WorkflowStatus status);

    @Query("""
            SELECT w FROM Workflow w
            WHERE w.scheduled = true
              AND w.scheduledAt IS NOT NULL
              AND w.scheduledAt <= :now
            """)
    List<Workflow> findDueScheduledWorkflows(
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("""
            UPDATE Workflow w
            SET w.scheduled = false
            WHERE w.id = :workflowId
              AND w.scheduled = true
            """)
    int claimScheduledWorkflow(
            @Param("workflowId") Long workflowId
    );

    @Modifying
    @Query("""
        UPDATE Workflow w
        SET w.scheduled = true
        WHERE w.id = :workflowId
          AND w.status = com.orynx.orchestrator.workflow.WorkflowStatus.CREATED
        """)
    int releaseScheduledWorkflow(@Param("workflowId") Long workflowId);
}