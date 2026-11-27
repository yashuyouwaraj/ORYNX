package com.orynx.execution.checkpoint;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "workflow_task_checkpoints",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_wf_task_checkpoint",
                        columnNames = {"workflow_id", "task_key"}
                )
        },
        indexes = {
                @Index(name = "idx_wf_task_cp_wf", columnList = "workflow_id"),
                @Index(name = "idx_wf_task_cp_wf_status", columnList = "workflow_id, status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowTaskCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "task_key", nullable = false, length = 64)
    private String taskKey;

    @Column(name = "task_name", length = 255)
    private String taskName;

    @Column(name = "task_type", length = 32)
    private String taskType;

    @Column(name = "execution_order")
    private Integer executionOrder;

    @Column(name = "depends_on_json", columnDefinition = "TEXT")
    private String dependsOnJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TaskCheckpointStatus status;

    @Column(name = "execution_generation", nullable = false)
    private Long executionGeneration;

    @Column(name = "worker_id", length = 128)
    private String workerId;

    @Column(name = "attempt", nullable = false)
    @Builder.Default
    private Integer attempt = 0;

    @Column(name = "output_json", columnDefinition = "TEXT")
    private String outputJson;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
