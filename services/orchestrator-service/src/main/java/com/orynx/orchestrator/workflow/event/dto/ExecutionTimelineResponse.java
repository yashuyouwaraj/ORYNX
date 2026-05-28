package com.orynx.orchestrator.workflow.event.dto;


import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ExecutionTimelineResponse {

    private String taskName;

    private String status;

    private Long timestamp;
}
