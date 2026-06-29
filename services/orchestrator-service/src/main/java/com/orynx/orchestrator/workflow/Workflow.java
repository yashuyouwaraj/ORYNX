package com.orynx.orchestrator.workflow;

import jakarta.persistence.*;
import lombok.*;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;

import java.time.LocalDateTime;

@Entity
@Table(name = "workflows")
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
}
