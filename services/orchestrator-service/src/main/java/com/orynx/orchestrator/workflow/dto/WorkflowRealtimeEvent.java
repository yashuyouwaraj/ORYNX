package com.orynx.orchestrator.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

@Getter
@Service
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowRealtimeEvent {
    private Long workflowId;
    private String workflowName;
    private String goal;
    private String status;
}
