package com.orynx.execution.coordination;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WorkflowExecutionCoordinationRepository extends JpaRepository<WorkflowExecutionCoordination, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM WorkflowExecutionCoordination c WHERE c.workflowId = :workflowId")
    Optional<WorkflowExecutionCoordination> findByWorkflowIdForUpdate(@Param("workflowId") Long workflowId);
}
