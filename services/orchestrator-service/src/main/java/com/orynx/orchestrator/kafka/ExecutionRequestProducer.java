package com.orynx.orchestrator.kafka;

import com.orynx.orchestrator.workflow.event.WorkflowExecutionRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutionRequestProducer {
    private final KafkaTemplate<String, WorkflowExecutionRequestEvent> kafkaTemplate;

    public static final String TOPIC = "workflow-execution";

    public void publishExecutionRequest(WorkflowExecutionRequestEvent event) {
        if (event == null || event.getWorkflowId() == null) {
            log.warn("Attempted to publish null or unkeyed WorkflowExecutionRequestEvent (workflowId=null). Skipping.");
            return;
        }
        String key = resolveKey(event.getWorkflowId());
        CompletableFuture<SendResult<String, WorkflowExecutionRequestEvent>> future =
                kafkaTemplate.send(TOPIC, key, event);
        if (future != null) {
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to deliver WorkflowExecutionRequestEvent for workflow {} (key={}, eventId={}): {}",
                            event.getWorkflowId(), key, event.getEventId(), ex.getMessage(), ex);
                } else if (result != null) {
                    log.debug("Delivered WorkflowExecutionRequestEvent for workflow {} to partition {} offset {}",
                            event.getWorkflowId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });
        }
        log.info("Published execution request for workflow {} (key={}, eventId={}, resume={})",
                event.getWorkflowId(), key, event.getEventId(), event.getResume());
    }

    private String resolveKey(Long workflowId) {
        return workflowId != null ? String.valueOf(workflowId) : null;
    }
}
