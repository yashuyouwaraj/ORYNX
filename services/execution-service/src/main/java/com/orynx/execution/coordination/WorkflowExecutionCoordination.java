package com.orynx.execution.coordination;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "workflow_execution_coordination")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowExecutionCoordination {

    @Id
    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "workflow_name")
    private String workflowName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ExecutionCoordinationStatus status;

    @Column(name = "instance_id", length = 128)
    private String instanceId;

    @Column(name = "last_completed_execution_order")
    private Integer lastCompletedExecutionOrder;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "generation", nullable = false)
    @Builder.Default
    private Long generation = 1L;

    @Column(name = "recovery_attempts", nullable = false)
    @Builder.Default
    private Integer recoveryAttempts = 0;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Version
    @Column(name = "version")
    private Long version;
}
