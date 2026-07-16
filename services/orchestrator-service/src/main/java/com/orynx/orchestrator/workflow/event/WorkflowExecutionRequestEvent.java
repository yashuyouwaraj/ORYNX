package com.orynx.orchestrator.workflow.event;

import com.orynx.orchestrator.workflow.event.dto.TaskExecutionRequest;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowExecutionRequestEvent {

    private Long workflowId;

    private String workflowName;

    private List<TaskExecutionRequest> tasks;

}