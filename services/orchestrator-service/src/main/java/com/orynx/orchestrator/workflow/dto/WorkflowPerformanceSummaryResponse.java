package com.orynx.orchestrator.workflow.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowPerformanceSummaryResponse {
    private String fastestWorkflow;
    private Long fastestDuration;
    private String slowestWorkflow;
    private Long slowestDuration;
    private Double averageDuration;
    private Long completedWorkflowCount;
}
