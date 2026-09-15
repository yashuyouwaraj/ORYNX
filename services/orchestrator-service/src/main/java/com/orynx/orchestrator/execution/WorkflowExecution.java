package com.orynx.orchestrator.execution;

import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "workflow_executions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id")
    private Workflow workflow;

    @Enumerated(EnumType.STRING)
    private WorkflowStatus status;

    private Long startedAt;

    private Long completedAt;

    private Long durationMs;

    private Integer lastCompletedExecutionOrder;
}
