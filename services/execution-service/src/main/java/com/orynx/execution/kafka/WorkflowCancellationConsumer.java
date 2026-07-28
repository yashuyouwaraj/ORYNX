package com.orynx.execution.kafka;

import com.orynx.execution.event.dto.WorkflowCancelledEvent;
import com.orynx.execution.event.dto.WorkflowCompletedEvent;
import com.orynx.execution.execution.WorkflowCancellationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowCancellationConsumer {

    private final WorkflowCancellationRegistry registry;

    @KafkaListener(
            topics = "workflow-cancelled",
            groupId = "execution-service"
    )
    public void consume(WorkflowCancelledEvent event) {

        registry.cancel(event.getWorkflowId());

        log.info(
                "Received cancellation for workflow {}",
                event.getWorkflowName()
        );
    }
}