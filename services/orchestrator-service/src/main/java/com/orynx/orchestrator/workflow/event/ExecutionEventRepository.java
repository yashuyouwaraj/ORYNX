package com.orynx.orchestrator.workflow.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExecutionEventRepository extends JpaRepository<ExecutionEvent, Long> {
    List<ExecutionEvent> findByWorkflowIdOrderByTimestampDesc(Long workflowId);
}
