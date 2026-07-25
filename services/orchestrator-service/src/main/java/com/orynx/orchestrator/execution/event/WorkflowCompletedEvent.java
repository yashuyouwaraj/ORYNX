package com.orynx.orchestrator.execution.event;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowCompletedEvent {
    private Long workflowId;
    private String workflowName;
    private boolean success;
}


