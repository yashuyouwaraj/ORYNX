package com.orynx.execution.coordination;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowExecutionCoordinationRepository extends JpaRepository<WorkflowExecutionCoordination, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM WorkflowExecutionCoordination c WHERE c.workflowId = :workflowId")
    Optional<WorkflowExecutionCoordination> findByWorkflowIdForUpdate(@Param("workflowId") Long workflowId);

    @Modifying
    @Query("UPDATE WorkflowExecutionCoordination c SET c.updatedAt = :now, c.leaseExpiresAt = :expiresAt " +
           "WHERE c.workflowId = :workflowId AND c.generation = :generation AND c.instanceId = :instanceId AND c.status = 'RUNNING'")
    int updateHeartbeat(
            @Param("workflowId") Long workflowId,
            @Param("generation") Long generation,
            @Param("instanceId") String instanceId,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt
    );

    @Modifying
    @Query("UPDATE WorkflowExecutionCoordination c SET c.status = 'RUNNING', c.updatedAt = :now, c.leaseExpiresAt = :expiresAt " +
           "WHERE c.workflowId = :workflowId AND c.generation = :generation AND c.instanceId = :instanceId")
    int markRunningFenced(
            @Param("workflowId") Long workflowId,
            @Param("generation") Long generation,
            @Param("instanceId") String instanceId,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt
    );

    @Modifying
    @Query("UPDATE WorkflowExecutionCoordination c SET c.status = :status, c.updatedAt = :now, c.leaseExpiresAt = null " +
           "WHERE c.workflowId = :workflowId AND c.generation = :generation AND c.instanceId = :instanceId " +
           "AND c.status NOT IN ('CANCELLED', 'COMPLETED')")
    int markCompletedFenced(
            @Param("workflowId") Long workflowId,
            @Param("generation") Long generation,
            @Param("instanceId") String instanceId,
            @Param("status") ExecutionCoordinationStatus status,
            @Param("now") Instant now
    );

    @Modifying
    @Query("UPDATE WorkflowExecutionCoordination c SET c.status = 'PAUSED', c.lastCompletedExecutionOrder = :order, c.updatedAt = :now, c.leaseExpiresAt = null " +
           "WHERE c.workflowId = :workflowId AND c.generation = :generation AND c.instanceId = :instanceId " +
           "AND c.status NOT IN ('CANCELLED', 'COMPLETED', 'FAILED')")
    int markPausedFenced(
            @Param("workflowId") Long workflowId,
            @Param("generation") Long generation,
            @Param("instanceId") String instanceId,
            @Param("order") Integer order,
            @Param("now") Instant now
    );

    @Query("SELECT c FROM WorkflowExecutionCoordination c WHERE c.status IN ('DISPATCHED', 'RUNNING') AND c.leaseExpiresAt < :now")
    List<WorkflowExecutionCoordination> findAbandonedExecutions(@Param("now") Instant now);
}
