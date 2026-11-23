package com.orynx.orchestrator.workflow;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, Long> {

    Optional<Workflow> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Workflow w WHERE w.id = :id AND w.userId = :userId")
    Optional<Workflow> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Workflow w WHERE w.id = :id")
    Optional<Workflow> findByIdForUpdate(@Param("id") Long id);

    @Override
    @EntityGraph(attributePaths = "dependsOnWorkflow")
    Page<Workflow> findAll(Pageable pageable);

    Page<Workflow> findByUserId(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = "dependsOnWorkflow")
    Page<Workflow> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = "dependsOnWorkflow")
    Page<Workflow> findByUserIdAndStatus(Long userId, WorkflowStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "dependsOnWorkflow")
    Page<Workflow> findByStatus(WorkflowStatus status, Pageable pageable);

    List<Workflow> findByUserIdOrderByIdDesc(Long userId);

    List<Workflow> findByUserIdAndStatusOrderByIdDesc(Long userId, WorkflowStatus status);

    long countByUserId(Long userId);

    long countByUserIdAndStatus(Long userId, WorkflowStatus status);

    List<Workflow> findAllByOrderByIdDesc();

    List<Workflow> findByDependsOnWorkflowId(Long workflowId);

    List<Workflow> findByStatusOrderByIdDesc(WorkflowStatus status);

    long countByStatus(WorkflowStatus status);

    @Query("SELECT w.status, COUNT(w) FROM Workflow w GROUP BY w.status")
    List<Object[]> countGroupedByStatus();

    @Query("SELECT w.status, COUNT(w) FROM Workflow w WHERE w.userId = :userId GROUP BY w.status")
    List<Object[]> countGroupedByStatusByUserId(@Param("userId") Long userId);

    @Query("SELECT w FROM Workflow w WHERE w.startedAt IS NOT NULL AND w.completedAt IS NOT NULL ORDER BY (w.completedAt - w.startedAt) ASC, w.id DESC")
    List<Workflow> findCompletedWorkflowsOrderByDurationAsc(Pageable pageable);

    @Query("SELECT w FROM Workflow w WHERE w.startedAt IS NOT NULL AND w.completedAt IS NOT NULL ORDER BY (w.completedAt - w.startedAt) DESC, w.id DESC")
    List<Workflow> findCompletedWorkflowsOrderByDurationDesc(Pageable pageable);

    @Query("SELECT COUNT(w), MIN(w.completedAt - w.startedAt), MAX(w.completedAt - w.startedAt), AVG(w.completedAt - w.startedAt) FROM Workflow w WHERE w.startedAt IS NOT NULL AND w.completedAt IS NOT NULL")
    List<Object[]> findCompletedWorkflowDurationAggregates();

    @Query("""
            SELECT w FROM Workflow w
            WHERE w.scheduled = true
              AND w.scheduledAt IS NOT NULL
              AND w.scheduledAt <= :now
              AND w.status = com.orynx.orchestrator.workflow.WorkflowStatus.CREATED
            """)
    List<Workflow> findDueScheduledWorkflows(
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Transactional
    @Query("""
            UPDATE Workflow w
            SET w.scheduled = false
            WHERE w.id = :workflowId
              AND w.scheduled = true
              AND w.status = com.orynx.orchestrator.workflow.WorkflowStatus.CREATED
            """)
    int claimScheduledWorkflow(
            @Param("workflowId") Long workflowId
    );

    @Modifying
    @Transactional
    @Query("""
        UPDATE Workflow w
        SET w.scheduled = true
        WHERE w.id = :workflowId
          AND w.status = com.orynx.orchestrator.workflow.WorkflowStatus.CREATED
        """)
    int releaseScheduledWorkflow(@Param("workflowId") Long workflowId);
}
