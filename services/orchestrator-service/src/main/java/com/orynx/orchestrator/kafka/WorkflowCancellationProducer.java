package com.orynx.orchestrator.kafka;

import com.orynx.orchestrator.event.dto.WorkflowCancelledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowCancellationProducer {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishWorkflowCancelled(WorkflowCancelledEvent event){
        kafkaTemplate.send(
                "workflow-cancelled",
                event
        );

        log.info(
                "Published WorkflowCancelledEvent for {}",
                event.getWorkflowName()
        );
    }
}
