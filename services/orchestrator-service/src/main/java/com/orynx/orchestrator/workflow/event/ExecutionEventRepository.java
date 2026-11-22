package com.orynx.orchestrator.workflow.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExecutionEventRepository extends JpaRepository<ExecutionEvent, Long> {
    List<ExecutionEvent> findByWorkflowIdOrderByTimestampDesc(Long workflowId);

    Optional<ExecutionEvent> findFirstByWorkflowIdAndTaskIdOrderByIdDesc(Long workflowId, Long taskId);

    Optional<ExecutionEvent> findFirstByWorkflowIdAndTaskNameOrderByIdDesc(Long workflowId, String taskName);
}
