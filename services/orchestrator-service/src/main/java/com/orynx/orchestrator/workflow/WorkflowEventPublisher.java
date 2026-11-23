package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.event.WorkflowExecutionEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class WorkflowEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final WorkflowRepository workflowRepository;

    public WorkflowEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this(messagingTemplate, null);
    }

    @Autowired
    public WorkflowEventPublisher(
            SimpMessagingTemplate messagingTemplate,
            WorkflowRepository workflowRepository
    ) {
        this.messagingTemplate = messagingTemplate;
        this.workflowRepository = workflowRepository;
    }

    public void publishWorkflowUpdate(WorkflowExecutionEvent event) {
        if (event == null || event.getWorkflowId() == null) {
            return;
        }
        if (event.getTimestamp() == null) {
            event.setTimestamp(System.currentTimeMillis());
        }
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            event.setEventId(UUID.randomUUID().toString());
        }

        messagingTemplate.convertAndSend(
                "/topic/workflows/" + event.getWorkflowId(),
                event
        );

        Long ownerUserId = event.getUserId();
        if (ownerUserId == null && workflowRepository != null) {
            ownerUserId = workflowRepository.findById(event.getWorkflowId())
                    .map(Workflow::getUserId)
                    .orElse(null);
        }
        if (ownerUserId != null) {
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(ownerUserId),
                    "/queue/workflows",
                    event
            );
        }
    }
}
