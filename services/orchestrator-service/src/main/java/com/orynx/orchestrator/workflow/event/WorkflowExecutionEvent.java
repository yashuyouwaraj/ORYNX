package com.orynx.orchestrator.workflow.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowExecutionEvent {
    private Long workflowId;
    private String workflowName;
    private String status;
    private Long generation;
    private Long timestamp;
    private String eventId;

    @JsonIgnore
    private Long userId;
}
