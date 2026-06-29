package com.orynx.orchestrator.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class AiWorkflowRequest {
    private String name;

    @NotBlank
    private String goal;

    private LocalDateTime scheduledAt;
}
