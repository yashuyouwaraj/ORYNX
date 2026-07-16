package com.orynx.execution.event.dto;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class WorkflowCompletedEvent {
    private Long workflowId;
    private String workflowName;
    private boolean success;
}
