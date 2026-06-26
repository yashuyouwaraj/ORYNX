package com.orynx.orchestrator.workflow;

import jakarta.persistence.*;
import lombok.*;

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

    private Long startedAt;

    private Long completedAt;
}
