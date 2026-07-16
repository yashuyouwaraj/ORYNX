package com.orynx.orchestrator.kafka;

import com.orynx.orchestrator.workflow.event.WorkflowExecutionRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutionRequestProducer {
    private final KafkaTemplate<String, WorkflowExecutionRequestEvent> kafkaTemplate;

    private static final String TOPIC="workflow-execution";

    public void publishExecutionRequest(WorkflowExecutionRequestEvent event){
        kafkaTemplate.send(TOPIC,event);
        log.info("Published execution request for workflow {}",
                event.getWorkflowId());
    }

}
