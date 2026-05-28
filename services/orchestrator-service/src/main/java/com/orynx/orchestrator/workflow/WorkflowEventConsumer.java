package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.dto.WorkflowRealtimeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class WorkflowEventConsumer {

    private final SimpMessagingTemplate
            messagingTemplate;

    public WorkflowEventConsumer(
            SimpMessagingTemplate messagingTemplate
    ) {

        this.messagingTemplate =
                messagingTemplate;
    }

    @KafkaListener(
            topics = "workflow-events",
            groupId = "orynx-workflow-group",
            containerFactory =
                    "kafkaListenerContainerFactory"
    )
    public void consume(
            WorkflowRealtimeEvent event
    ) {

        log.info(
                "Workflow Event: {}",
                event.getWorkflowName()
        );

        messagingTemplate.convertAndSend(
                "/topic/workflows",
                event
        );
    }
}