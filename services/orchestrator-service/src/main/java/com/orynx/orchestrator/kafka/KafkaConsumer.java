package com.orynx.orchestrator.kafka;

import com.orynx.orchestrator.workflow.event.WorkflowCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class KafkaConsumer {
    @KafkaListener(topics = "workflow-created",groupId = "orynx-orchestrator-group")
    public void consume(WorkflowCreatedEvent event){
        log.info("Workflow Event Received: {}",event.getWorkflowName());
    }
}
