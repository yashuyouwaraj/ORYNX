package com.orynx.orchestrator.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateWorkflowTaskRequest {

    private String taskKey;

    @NotBlank
    private String name;

    private String taskType;

    private List<String> dependsOn;

    private Map<String, Object> config;

    private Long timeoutMs;

    private Integer maxRetries;

    private Integer executionOrder;
}
