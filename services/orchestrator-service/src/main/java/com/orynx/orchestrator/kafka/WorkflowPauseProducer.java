package com.orynx.orchestrator.kafka;

import com.orynx.orchestrator.event.dto.WorkflowPausedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowPauseProducer {
    public static final String TOPIC = "workflow-paused";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishWorkflowPaused(WorkflowPausedEvent event) {
        if (event == null || event.getWorkflowId() == null) {
            log.warn("Attempted to publish null or unkeyed WorkflowPausedEvent (workflowId=null). Skipping.");
            return;
        }
        String key = event.getWorkflowId() != null ? String.valueOf(event.getWorkflowId()) : null;
        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(TOPIC, key, event);
        if (future != null) {
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to deliver WorkflowPausedEvent for workflow {} (key={}, eventId={}): {}",
                            event.getWorkflowId(), key, event.getEventId(), ex.getMessage(), ex);
                }
            });
        }

        log.info("Published WorkflowPausedEvent for {} (key={}, eventId={})",
                event.getWorkflowName(), key, event.getEventId());
    }
}
