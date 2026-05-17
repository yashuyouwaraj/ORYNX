package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.event.WorkflowExecutionEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkflowEventPublisher {
    private final SimpMessagingTemplate messagingTemplate;

    public void publishWorkflowUpdate(WorkflowExecutionEvent event){
        messagingTemplate.convertAndSend("/topic/workflows",event);
    }
}
