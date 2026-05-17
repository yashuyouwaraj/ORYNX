package com.orynx.orchestrator.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateWorkflowRequest {
    @NotBlank
    private String name;

    @NotBlank
    private String goal;
}
