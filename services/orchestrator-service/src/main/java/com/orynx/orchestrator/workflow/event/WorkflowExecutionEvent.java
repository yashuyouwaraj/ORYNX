package com.orynx.orchestrator.workflow.event;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowExecutionEvent {
    private Long workflowId;
    private String workflowName;
    private String status;
}
