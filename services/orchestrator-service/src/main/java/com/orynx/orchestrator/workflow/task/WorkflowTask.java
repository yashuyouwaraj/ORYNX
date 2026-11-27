package com.orynx.orchestrator.workflow.task;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.orchestrator.workflow.Workflow;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Table(
        name = "workflow_tasks",
        indexes = {
                @Index(name = "idx_workflow_tasks_wf_order", columnList = "workflow_id, execution_order"),
                @Index(name = "idx_workflow_tasks_wf_key", columnList = "workflow_id, task_key"),
                @Index(name = "idx_workflow_tasks_status", columnList = "status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowTask {
    private static final ObjectMapper DEPENDENCY_MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_key", length = 64)
    private String taskKey;

    private String name;

    @Column(name = "task_type", length = 32)
    @Builder.Default
    private String taskType = "NOOP";

    @Enumerated(EnumType.STRING)
    private TaskStatus status;

    private Integer executionOrder;

    @Column(name = "depends_on_json", columnDefinition = "TEXT")
    @JsonIgnore
    private String dependsOnJson;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "timeout_ms")
    @Builder.Default
    private Long timeoutMs = 30_000L;

    @Column(name = "output_json", columnDefinition = "TEXT")
    private String outputJson;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

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

    @JsonProperty("dependsOn")
    @Transient
    public List<String> getDependsOn() {
        if (dependsOnJson == null || dependsOnJson.isBlank()) {
            return List.of();
        }
        try {
            return DEPENDENCY_MAPPER.readValue(dependsOnJson, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @PrePersist
    @PostLoad
    void initDefaults() {
        if (retryCount == null) {
            retryCount = 0;
        }
        if (maxRetries == null) {
            maxRetries = 3;
        }
        if (taskType == null || taskType.isBlank()) {
            taskType = "NOOP";
        }
        if (timeoutMs == null) {
            timeoutMs = 30_000L;
        }
        if ((taskKey == null || taskKey.isBlank()) && executionOrder != null) {
            taskKey = "task-" + executionOrder;
        }
    }
}
