package com.orynx.orchestrator.workflow.execution.dto;


import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowAnalyticsResponse {

    private Long workflowId;

    private String workflowName;

    private long totalExecutions;

    private long successfulExecutions;

    private long failedExecutions;

    private double successRate;

    private long averageDurationMs;

    private long fastestExecutionMs;

    private long slowestExecutionMs;

}