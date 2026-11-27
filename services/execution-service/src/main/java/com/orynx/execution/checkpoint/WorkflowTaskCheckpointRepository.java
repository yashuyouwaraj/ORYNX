package com.orynx.execution.checkpoint;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowTaskCheckpointRepository extends JpaRepository<WorkflowTaskCheckpoint, Long> {

    List<WorkflowTaskCheckpoint> findByWorkflowId(Long workflowId);

    Optional<WorkflowTaskCheckpoint> findByWorkflowIdAndTaskKey(Long workflowId, String taskKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM WorkflowTaskCheckpoint c WHERE c.workflowId = :workflowId AND c.taskKey = :taskKey")
    Optional<WorkflowTaskCheckpoint> findByWorkflowIdAndTaskKeyForUpdate(
            @Param("workflowId") Long workflowId,
            @Param("taskKey") String taskKey
    );

    void deleteByWorkflowId(Long workflowId);
}
