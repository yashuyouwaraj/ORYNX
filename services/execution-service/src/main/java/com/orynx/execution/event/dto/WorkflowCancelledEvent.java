package com.orynx.execution.event.dto;


import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowCancelledEvent {
    private Long workflowId;
    private String workflowName;
}
