package com.orynx.orchestrator.workflow.event;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowCreatedEvent {
    private Long workflowId;
    private String workflowName;
    private String goal;
}
