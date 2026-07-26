package com.orynx.orchestrator.workflow.execution.dto;

import com.orynx.orchestrator.workflow.WorkflowStatus;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class WorkflowExecutionResponse {
    private Long id;
    private WorkflowStatus status;
    private Long startedAt;
    private Long completedAt;
}
