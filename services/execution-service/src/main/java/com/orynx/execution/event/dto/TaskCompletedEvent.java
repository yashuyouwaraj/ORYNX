package com.orynx.execution.event.dto;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TaskCompletedEvent {
    private Long workflowId;
    private String workflowName;
    private String taskName;
    private Integer executionOrder;
    private boolean success;
    private int attempts;
}
