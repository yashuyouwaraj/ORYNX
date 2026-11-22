package com.orynx.execution.kafka;

import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.event.dto.WorkflowResumedEvent;
import com.orynx.execution.execution.ActiveExecutionTracker;
import com.orynx.execution.execution.WorkflowPauseRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class WorkflowResumeConsumer {

    private final WorkflowPauseRegistry pauseRegistry;
    private final ExecutionCoordinationService coordinationService;
    private final ActiveExecutionTracker activeExecutionTracker;

    public WorkflowResumeConsumer(WorkflowPauseRegistry pauseRegistry) {
        this(pauseRegistry, null, null);
    }

    @Autowired
    public WorkflowResumeConsumer(
            WorkflowPauseRegistry pauseRegistry,
            ExecutionCoordinationService coordinationService,
            ActiveExecutionTracker activeExecutionTracker
    ) {
        this.pauseRegistry = pauseRegistry;
        this.coordinationService = coordinationService;
        this.activeExecutionTracker = activeExecutionTracker;
    }

    @KafkaListener(
            topics = "workflow-resumed",
            groupId = "execution-service",
            properties = {
                    "spring.json.value.default.type=com.orynx.execution.event.dto.WorkflowResumedEvent",
                    "spring.json.use.type.headers=false"
            }
    )
    public void consumeWorkflowResumed(WorkflowResumedEvent event, Acknowledgment acknowledgment) {
        if (event != null && event.getWorkflowId() != null) {
            Long workflowId = event.getWorkflowId();
            log.info(
                    "Workflow Resumed -> Workflow: {} (id={}, eventId={})",
                    event.getWorkflowName(),
                    workflowId,
                    event.getEventId()
            );

            if (coordinationService != null) {
                coordinationService.markResumed(workflowId);
            }
            if (activeExecutionTracker != null) {
                activeExecutionTracker.clearPause(workflowId);
            }
            pauseRegistry.clear(workflowId);

            log.info(
                    "Pause state cleared for workflow {} (id={})",
                    event.getWorkflowName(),
                    workflowId
            );
        }

        if (acknowledgment != null) {
            acknowledgment.acknowledge();
        }
    }
}
