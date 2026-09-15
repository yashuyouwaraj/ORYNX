package com.orynx.orchestrator.event.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowResumedEvent {
    private Long workflowId;
    private String workflowName;
}


