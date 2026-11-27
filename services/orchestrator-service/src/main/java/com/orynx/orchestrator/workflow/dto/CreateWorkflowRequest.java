package com.orynx.orchestrator.workflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class CreateWorkflowRequest {
    @NotBlank
    private String name;

    @NotBlank
    private String goal;

    private LocalDateTime scheduledAt;

    private Long dependsOnWorkflowId;

    @Valid
    private List<CreateWorkflowTaskRequest> tasks;
}
