package com.orynx.orchestrator.workflow.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowAnalyticsResponse {

    private Long totalWorkflows;

    private Long completedWorkflows;

    private Long runningWorkflows;

    private Long totalTasks;

    private Double averageExecutionTime;
}