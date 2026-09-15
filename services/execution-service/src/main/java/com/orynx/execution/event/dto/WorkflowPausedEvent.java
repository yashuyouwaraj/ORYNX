package com.orynx.execution.event.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowPausedEvent {

    private Long workflowId;

    private String workflowName;
}