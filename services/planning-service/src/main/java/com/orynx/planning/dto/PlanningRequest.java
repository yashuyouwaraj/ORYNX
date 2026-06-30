package com.orynx.planning.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PlanningRequest {
    @NotBlank
    private String goal;
}
