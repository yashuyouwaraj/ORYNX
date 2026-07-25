package com.orynx.execution.workflow.event.dto;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

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
