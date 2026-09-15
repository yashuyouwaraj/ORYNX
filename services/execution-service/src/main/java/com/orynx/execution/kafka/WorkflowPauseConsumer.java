package com.orynx.execution.kafka;

import com.orynx.execution.event.dto.WorkflowPausedEvent;
import com.orynx.execution.execution.WorkflowPauseRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowPauseConsumer {

    private final WorkflowPauseRegistry pauseRegistry;

    @KafkaListener(
            topics = "workflow-paused",
            groupId = "execution-service"
    )
    public void consume(WorkflowPausedEvent event) {

        pauseRegistry.pause(event.getWorkflowId());

        log.info(
                "Received pause request for workflow {}",
                event.getWorkflowName()
        );
    }
}