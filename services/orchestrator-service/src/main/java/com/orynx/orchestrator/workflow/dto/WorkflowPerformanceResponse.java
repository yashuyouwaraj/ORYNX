package com.orynx.orchestrator.workflow.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowPerformanceResponse {
    private String workflowName;
    private Long durationMs;
}
