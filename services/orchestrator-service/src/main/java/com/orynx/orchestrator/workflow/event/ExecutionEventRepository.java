package com.orynx.orchestrator.workflow.event;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExecutionEventRepository extends JpaRepository<ExecutionEvent, Long> {
    List<ExecutionEvent> findByWorkflowIdOrderByTimestampDesc(Long workflowId);

    Page<ExecutionEvent> findByWorkflowId(Long workflowId, Pageable pageable);

    Optional<ExecutionEvent> findFirstByWorkflowIdAndTaskIdOrderByIdDesc(Long workflowId, Long taskId);

    Optional<ExecutionEvent> findFirstByWorkflowIdAndTaskIdIsNullAndTaskNameOrderByIdDesc(Long workflowId, String taskName);

    Optional<ExecutionEvent> findFirstByWorkflowIdAndTaskNameOrderByIdDesc(Long workflowId, String taskName);

    boolean existsByWorkflowIdAndEventId(Long workflowId, String eventId);
}
