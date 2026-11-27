package com.orynx.execution.workflow.event.dto;

import lombok.*;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskExecutionRequest {

    private String taskKey;

    private String name;

    private String taskType;

    private List<String> dependsOn;

    private Map<String, Object> config;

    private Long timeoutMs;

    private Integer executionOrder;

    private Integer maxRetries;

}
