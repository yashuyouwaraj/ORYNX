package com.orynx.orchestrator.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface WorkflowRepository extends JpaRepository<Workflow, Long> {
    List<Workflow> findAllByOrderByIdDesc();
    List<Workflow> findByDependsOnWorkflowId(Long workflowId);

    @Query("""
            SELECT w FROM Workflow w
            WHERE w.scheduled = true
              AND w.scheduledAt IS NOT NULL
              AND w.scheduledAt <= :now
            """)
    List<Workflow> findDueScheduledWorkflows(@Param("now") LocalDateTime now);
}
