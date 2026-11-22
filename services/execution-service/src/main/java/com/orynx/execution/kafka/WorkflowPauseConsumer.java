package com.orynx.execution.kafka;

import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.event.dto.WorkflowPausedEvent;
import com.orynx.execution.execution.ActiveExecutionTracker;
import com.orynx.execution.execution.WorkflowPauseRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowPauseConsumer {

    private final WorkflowPauseRegistry pauseRegistry;
    private final ActiveExecutionTracker activeExecutionTracker;
    private final ExecutionCoordinationService coordinationService;

    @KafkaListener(
            topics = "workflow-paused",
            groupId = "execution-service"
    )
    public void consume(WorkflowPausedEvent event, Acknowledgment acknowledgment) {
        if (event != null && event.getWorkflowId() != null) {
            Long workflowId = event.getWorkflowId();
            if (coordinationService.isCancelled(workflowId)) {
                log.info("Ignoring pause request for workflow {} (id={}) because workflow is already CANCELLED.",
                        event.getWorkflowName(), workflowId);
                if (acknowledgment != null) {
                    acknowledgment.acknowledge();
                }
                return;
            }
            log.info(
                    "Received pause request for workflow {} (id={})",
                    event.getWorkflowName(),
                    workflowId
            );

            // 1. Mark paused in distributed coordination store
            coordinationService.markPaused(workflowId, null);

            // 2. Mark active execution tracker for active local thread
            activeExecutionTracker.pause(workflowId);

            // 3. In-memory registry
            pauseRegistry.pause(workflowId);
        }

        if (acknowledgment != null) {
            acknowledgment.acknowledge();
        }
    }
}
