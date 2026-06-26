package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.dto.WorkflowRealtimeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowEventProducer {
    private final KafkaTemplate<String, WorkflowRealtimeEvent> kafkaTemplate;

    public void publishWorkflowEvent(WorkflowRealtimeEvent event) {
        try {
            kafkaTemplate.send("workflow-events", event);
        } catch (Exception ex) {
            log.warn("Failed to publish workflow event for workflow {} — continuing execution", event.getWorkflowId(), ex);
        }
    }
}
