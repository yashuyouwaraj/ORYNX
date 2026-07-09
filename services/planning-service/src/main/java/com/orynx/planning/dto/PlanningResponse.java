package com.orynx.planning.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class PlanningResponse {
    private String workflowName;
    private List<String> tasks;
}

