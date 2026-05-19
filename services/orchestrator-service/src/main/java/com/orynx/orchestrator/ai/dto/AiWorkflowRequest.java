package com.orynx.orchestrator.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AiWorkflowRequest {
    @NotBlank
    private String goal;
}
