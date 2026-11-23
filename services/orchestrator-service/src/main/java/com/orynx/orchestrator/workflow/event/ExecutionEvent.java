package com.orynx.orchestrator.workflow.event;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "execution_events",
        indexes = {
                @Index(name = "idx_execution_events_wf_ts", columnList = "workflow_id, timestamp, id"),
                @Index(name = "idx_execution_events_wf_task_id", columnList = "workflow_id, task_id, id"),
                @Index(name = "idx_execution_events_wf_task_name", columnList = "workflow_id, task_name, id"),
                @Index(name = "idx_execution_events_wf_event_id", columnList = "workflow_id, event_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workflowId;

    private Long taskId;

    private String taskName;

    private String status;

    private Long timestamp;

    private Long generation;

    private String eventId;
}
