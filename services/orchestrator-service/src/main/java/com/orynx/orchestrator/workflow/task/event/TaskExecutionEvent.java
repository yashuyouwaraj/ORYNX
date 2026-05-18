package com.orynx.orchestrator.workflow.task.event;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TaskExecutionEvent {
    private Long workflowId;
    private String taskName;
    private String status;
}
