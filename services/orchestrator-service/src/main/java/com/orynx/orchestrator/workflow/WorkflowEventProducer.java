package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.dto.WorkflowRealtimeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowEventProducer {
    public static final String TOPIC = "workflow-events";

    private final KafkaTemplate<String, WorkflowRealtimeEvent> kafkaTemplate;

    public void publishWorkflowEvent(WorkflowRealtimeEvent event) {
        if (event == null) {
            return;
        }
        String key = event.getWorkflowId() != null ? String.valueOf(event.getWorkflowId()) : null;
        try {
            CompletableFuture<SendResult<String, WorkflowRealtimeEvent>> future =
                    kafkaTemplate.send(TOPIC, key, event);
            if (future != null) {
                future.whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Async delivery failure for workflow event {} (key={}): {}",
                                event.getWorkflowId(), key, ex.getMessage());
                    }
                });
            }
        } catch (Exception ex) {
            log.warn("Failed to publish workflow event for workflow {} — continuing execution", event.getWorkflowId(), ex);
        }
    }
}
