package com.orynx.orchestrator.workflow.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowHealthResponse {
    private String workflowName;
    private Long durationMs;
    private String health;
    private String reason;
}
