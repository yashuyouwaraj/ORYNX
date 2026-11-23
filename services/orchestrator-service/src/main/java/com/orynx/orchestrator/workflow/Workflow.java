package com.orynx.orchestrator.workflow;

import jakarta.persistence.*;
import lombok.*;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "workflows",
        indexes = {
                @Index(name = "idx_workflows_user_id", columnList = "user_id"),
                @Index(name = "idx_workflows_user_id_id", columnList = "user_id, id"),
                @Index(name = "idx_workflows_user_status_id", columnList = "user_id, status, id"),
                @Index(name = "idx_workflows_status_id", columnList = "status, id"),
                @Index(name = "idx_workflows_scheduled_due", columnList = "scheduled, status, scheduled_at"),
                @Index(name = "idx_workflows_depends_on", columnList = "depends_on_workflow_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Workflow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String goal;

    @Enumerated(EnumType.STRING)
    private WorkflowStatus status;

    private LocalDateTime scheduledAt;

    @Builder.Default
    private Boolean scheduled = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "depends_on_workflow_id")
    private Workflow dependsOnWorkflow;

    private Long startedAt;

    private Long completedAt;

    @Column(name = "user_id", updatable = false)
    private Long userId;
}
