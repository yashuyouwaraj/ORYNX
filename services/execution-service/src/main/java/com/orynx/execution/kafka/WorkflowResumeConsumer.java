package com.orynx.execution.kafka;

import com.orynx.execution.event.dto.WorkflowResumedEvent;
import com.orynx.execution.execution.WorkflowPauseRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowResumeConsumer {

    private final WorkflowPauseRegistry pauseRegistry;

    @KafkaListener(
            topics = "workflow-resumed",
            groupId = "execution-service",
            properties = {
                    "spring.json.value.default.type=com.orynx.execution.event.dto.WorkflowResumedEvent",
                    "spring.json.use.type.headers=false"
            }
    )
    public void consumeWorkflowResumed(WorkflowResumedEvent event) {

        log.info(
                "Workflow Resumed -> Workflow: {}",
                event.getWorkflowName()
        );

        pauseRegistry.clear(event.getWorkflowId());

        log.info(
                "Pause registry cleared for workflow {}",
                event.getWorkflowName()
        );
    }
}