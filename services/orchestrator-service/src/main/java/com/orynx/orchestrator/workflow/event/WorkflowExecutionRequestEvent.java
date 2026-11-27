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

    private String eventId;

    private Long executionId;

    private Boolean resume;

    private Long generation;

    /**
     * Explicit execution intent: INITIAL, RETRY, RECOVERY, or RESUME.
     * Avoids overloading generation > 1 to distinguish user retry from crash recovery.
     */
    private String executionMode;

    /**
     * Optional list of taskKeys already confirmed COMPLETED in orchestrator-service
     * (used during DAG-aware RESUME alongside durable execution checkpoints).
     */
    private List<String> completedTaskKeys;

}