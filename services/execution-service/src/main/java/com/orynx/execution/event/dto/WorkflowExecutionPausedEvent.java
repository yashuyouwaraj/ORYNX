package com.orynx.execution.event.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowExecutionPausedEvent {

    private Long workflowId;

    private String workflowName;

    private Integer lastCompletedExecutionOrder;
}