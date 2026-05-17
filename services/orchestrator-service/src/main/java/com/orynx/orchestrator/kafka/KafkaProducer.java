package com.orynx.orchestrator.kafka;

import com.orynx.orchestrator.workflow.event.WorkflowCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KafkaProducer {
    private final KafkaTemplate<String, WorkflowCreatedEvent> kafkaTemplate;

    public void publishWorkflowCreatedEvent(WorkflowCreatedEvent event){
        kafkaTemplate.send("workflow-created",event);
    }
}
