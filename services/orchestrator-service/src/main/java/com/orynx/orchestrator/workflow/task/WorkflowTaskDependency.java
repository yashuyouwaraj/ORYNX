package com.orynx.orchestrator.workflow.task;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "workflow_task_dependencies",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_wf_task_dep",
                        columnNames = {"workflow_id", "task_key", "depends_on_task_key"}
                )
        },
        indexes = {
                @Index(name = "idx_wf_task_dep_wf", columnList = "workflow_id"),
                @Index(name = "idx_wf_task_dep_wf_task", columnList = "workflow_id, task_key")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowTaskDependency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "task_key", nullable = false, length = 64)
    private String taskKey;

    @Column(name = "depends_on_task_key", nullable = false, length = 64)
    private String dependsOnTaskKey;
}
