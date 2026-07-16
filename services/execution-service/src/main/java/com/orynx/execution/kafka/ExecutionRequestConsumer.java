package com.orynx.execution.kafka;

import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ExecutionRequestConsumer {
    @KafkaListener(topics = "workflow-execution",groupId = "execution-service")
    public void consume(WorkflowExecutionRequestEvent event){
        log.info(
                "Received workflow execution request: {} - {}",
                event.getWorkflowId(),
                event.getWorkflowName()
        );
    }
}
