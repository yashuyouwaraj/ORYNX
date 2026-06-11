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

    private Double workflowSuccessRate;

    private Double workflowFailureRate;

    private Long executedTaskCount;

    private Long eventCount;
}