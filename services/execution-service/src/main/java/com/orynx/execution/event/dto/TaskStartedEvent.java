package com.orynx.execution.event.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskStartedEvent {
    private Long workflowId;
    private String workflowName;
    private String taskKey;
    private String taskName;
    private String taskType;
    private Integer executionOrder;
    private Long generation;
    private String eventId;
}
