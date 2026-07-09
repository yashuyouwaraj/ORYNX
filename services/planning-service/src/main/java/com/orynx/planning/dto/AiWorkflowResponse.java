package com.orynx.planning.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiWorkflowResponse {
    private String workflowName;
    private List<String> tasks;
}
