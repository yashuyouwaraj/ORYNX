package com.orynx.orchestrator.kafka;

import com.orynx.orchestrator.event.dto.WorkflowResumedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowResumeProducer {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishWorkflowResumed(WorkflowResumedEvent event){
        kafkaTemplate.send(
                "workflow-resumed",
                event
        );

        log.info(
                "Published WorkflowResumedEvent for {}",
                event.getWorkflowName()
        );
    }
}
