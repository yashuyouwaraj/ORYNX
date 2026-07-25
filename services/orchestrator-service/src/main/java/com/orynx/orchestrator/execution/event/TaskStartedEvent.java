package com.orynx.orchestrator.execution.event;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskStartedEvent {
    private Long workflowId;
    private String workflowName;
    private String taskName;
    private Integer executionOrder;
}
