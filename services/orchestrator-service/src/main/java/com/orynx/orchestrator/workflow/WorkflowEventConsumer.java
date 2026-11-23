package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.dto.WorkflowRealtimeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class WorkflowEventConsumer {

    private final SimpMessagingTemplate messagingTemplate;
    private final WorkflowRepository workflowRepository;

    public WorkflowEventConsumer(SimpMessagingTemplate messagingTemplate) {
        this(messagingTemplate, null);
    }

    @Autowired
    public WorkflowEventConsumer(
            SimpMessagingTemplate messagingTemplate,
            WorkflowRepository workflowRepository
    ) {
        this.messagingTemplate = messagingTemplate;
        this.workflowRepository = workflowRepository;
    }

    @KafkaListener(
            topics = "workflow-events",
            groupId = "orynx-workflow-group",
            containerFactory = "kafkaListenerContainerFactory",
            properties = {
                    "spring.json.value.default.type=com.orynx.orchestrator.workflow.dto.WorkflowRealtimeEvent",
                    "spring.json.use.type.headers=false"
            }
    )
    public void consume(WorkflowRealtimeEvent event) {
        if (event == null || event.getWorkflowId() == null) {
            log.warn("Received null or malformed WorkflowRealtimeEvent. Ignoring.");
            return;
        }

        log.info("Workflow Event: {} ({})", event.getWorkflowName(), event.getWorkflowId());

        messagingTemplate.convertAndSend(
                "/topic/workflows/" + event.getWorkflowId(),
                event
        );

        if (workflowRepository != null) {
            workflowRepository.findById(event.getWorkflowId())
                    .map(Workflow::getUserId)
                    .ifPresent(userId -> messagingTemplate.convertAndSendToUser(
                            String.valueOf(userId),
                            "/queue/workflows",
                            event
                    ));
        }
    }
}
