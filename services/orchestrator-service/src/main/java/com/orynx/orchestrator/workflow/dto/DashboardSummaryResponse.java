package com.orynx.orchestrator.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardSummaryResponse {
    private long totalWorkflows;
    private long runningWorkflows;
    private long pausedWorkflows;
    private long completedWorkflows;
    private long failedWorkflows;
    private long createdWorkflows;
    private long cancelledWorkflows;
}
