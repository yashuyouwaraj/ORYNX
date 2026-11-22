package com.orynx.orchestrator.kafka;

import com.orynx.orchestrator.workflow.event.WorkflowCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducer {
    public static final String TOPIC = "workflow-created";

    private final KafkaTemplate<String, WorkflowCreatedEvent> kafkaTemplate;

    public void publishWorkflowCreatedEvent(WorkflowCreatedEvent event) {
        if (event == null || event.getWorkflowId() == null) {
            log.warn("Attempted to publish null or unkeyed WorkflowCreatedEvent (workflowId=null). Skipping.");
            return;
        }
        String key = event.getWorkflowId() != null ? String.valueOf(event.getWorkflowId()) : null;
        CompletableFuture<SendResult<String, WorkflowCreatedEvent>> future =
                kafkaTemplate.send(TOPIC, key, event);
        if (future != null) {
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to deliver WorkflowCreatedEvent for workflow {} (key={}): {}",
                            event.getWorkflowId(), key, ex.getMessage(), ex);
                }
            });
        }
    }
}
