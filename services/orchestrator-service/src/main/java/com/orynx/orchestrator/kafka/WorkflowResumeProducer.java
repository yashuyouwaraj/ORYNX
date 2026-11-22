package com.orynx.orchestrator.kafka;

import com.orynx.orchestrator.event.dto.WorkflowResumedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowResumeProducer {
    public static final String TOPIC = "workflow-resumed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishWorkflowResumed(WorkflowResumedEvent event) {
        if (event == null || event.getWorkflowId() == null) {
            log.warn("Attempted to publish null or unkeyed WorkflowResumedEvent (workflowId=null). Skipping.");
            return;
        }
        String key = event.getWorkflowId() != null ? String.valueOf(event.getWorkflowId()) : null;
        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(TOPIC, key, event);
        if (future != null) {
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to deliver WorkflowResumedEvent for workflow {} (key={}, eventId={}): {}",
                            event.getWorkflowId(), key, event.getEventId(), ex.getMessage(), ex);
                }
            });
        }

        log.info("Published WorkflowResumedEvent for {} (key={}, eventId={})",
                event.getWorkflowName(), key, event.getEventId());
    }
}
