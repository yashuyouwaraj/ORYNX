package com.orynx.orchestrator.workflow.task.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskExecutionEvent {
    private Long workflowId;
    private Long taskId;
    private String taskName;
    private String status;
}
