package com.orynx.orchestrator.kafka;

import com.orynx.orchestrator.event.dto.WorkflowPausedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowPauseProducer {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishWorkflowPaused(WorkflowPausedEvent event){
        kafkaTemplate.send(
                "workflow-paused",
                event
        );

        log.info(
                "Published WorkflowPausedEvent for {}",
                event.getWorkflowName()
        );
    }
}
