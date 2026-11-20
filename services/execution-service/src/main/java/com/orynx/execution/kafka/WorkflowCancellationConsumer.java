package com.orynx.execution.kafka;

import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.event.dto.WorkflowCancelledEvent;
import com.orynx.execution.execution.ActiveExecutionTracker;
import com.orynx.execution.execution.WorkflowCancellationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowCancellationConsumer {

    private final WorkflowCancellationRegistry registry;
    private final ActiveExecutionTracker activeExecutionTracker;
    private final ExecutionCoordinationService coordinationService;

    @KafkaListener(
            topics = "workflow-cancelled",
            groupId = "execution-service"
    )
    public void consume(WorkflowCancelledEvent event, Acknowledgment acknowledgment) {
        if (event != null && event.getWorkflowId() != null) {
            Long workflowId = event.getWorkflowId();
            log.info(
                    "Received cancellation for workflow {} (id={})",
                    event.getWorkflowName(),
                    workflowId
            );

            // 1. Mark cancelled in distributed coordination store
            coordinationService.markCancelled(workflowId);

            // 2. Interrupt active local worker thread if executing locally
            activeExecutionTracker.cancel(workflowId);

            // 3. Mark in-memory registry for backward-compatibility
            registry.cancel(workflowId);
        }

        if (acknowledgment != null) {
            acknowledgment.acknowledge();
        }
    }
}
