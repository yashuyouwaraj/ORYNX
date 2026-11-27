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
    private String taskKey;
    private String taskName;
    private String taskType;
    private Integer executionOrder;
    private boolean success;
    private boolean skipped;
    private boolean cancelled;
    private int attempts;
    private Long generation;
    private String eventId;
    private String outputJson;
    private String errorMessage;
}
