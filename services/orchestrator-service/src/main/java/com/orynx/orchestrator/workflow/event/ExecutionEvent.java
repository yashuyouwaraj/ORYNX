package com.orynx.orchestrator.workflow.event;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "execution_events")
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
}
