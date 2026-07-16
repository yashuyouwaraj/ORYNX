package com.orynx.orchestrator.workflow.event.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskExecutionRequest {

    private String name;

    private Integer executionOrder;

    private Integer maxRetries;

}
