package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.dto.WorkflowRealtimeEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkflowEventProducer {
    private final KafkaTemplate<String, WorkflowRealtimeEvent> kafkaTemplate;

    public void publishWorkflowEvent(WorkflowRealtimeEvent event){
        kafkaTemplate.send("workflow-events",event);
    }
}
