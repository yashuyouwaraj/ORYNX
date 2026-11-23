package com.orynx.orchestrator.workflow.task;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.orynx.orchestrator.workflow.Workflow;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "workflow_tasks",
        indexes = {
                @Index(name = "idx_workflow_tasks_wf_order", columnList = "workflow_id, execution_order"),
                @Index(name = "idx_workflow_tasks_status", columnList = "status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private TaskStatus status;

    private Integer executionOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id")
    @JsonIgnore
    private Workflow workflow;

    private Long startedAt;

    private Long completedAt;

    @Builder.Default
    private Integer retryCount = 0;

    @Builder.Default
    private Integer maxRetries = 3;

    @PrePersist
    @PostLoad
    void initDefaults() {
        if (retryCount == null) {
            retryCount = 0;
        }
        if (maxRetries == null) {
            maxRetries = 3;
        }
    }
}
