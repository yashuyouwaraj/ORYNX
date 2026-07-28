package com.orynx.orchestrator.workflow.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardSummaryResponse {
    private long totalWorkflows;
    private long runningWorkflows;
    private long completedWorkflows;
    private long failedWorkflows;
    private long createdWorkflows;
    private long cancelledWorkflows;
}
