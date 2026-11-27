package com.orynx.execution.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.coordination.WorkflowExecutionCoordination;
import com.orynx.execution.event.dto.WorkflowCompletedEvent;
import com.orynx.execution.execution.WorkflowExecutionDispatcher;
import com.orynx.execution.kafka.ExecutionEventProducer;
import com.orynx.execution.worker.ExecutionWorkerProperties;
import com.orynx.execution.worker.WorkerQueueFullException;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "orynx.execution.worker.recovery-enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionRecoveryScanner {

    private final ExecutionCoordinationService coordinationService;
    private final WorkflowExecutionDispatcher dispatcher;
    private final ExecutionEventProducer executionEventProducer;
    private final ExecutionWorkerProperties properties;
    private final ObjectMapper objectMapper;

    @Scheduled(
            fixedDelayString = "${orynx.execution.worker.recovery-interval-seconds:60}000",
            initialDelayString = "${orynx.execution.worker.recovery-initial-delay-seconds:30}000"
    )
    public void scanAndRecoverAbandonedExecutions() {
        try {
            Instant now = Instant.now();
            List<WorkflowExecutionCoordination> abandoned = coordinationService.findAbandonedExecutions(now);

            if (abandoned.isEmpty()) {
                return;
            }

            log.warn("Discovered {} abandoned execution(s) with expired leases", abandoned.size());

            for (WorkflowExecutionCoordination c : abandoned) {
                recoverExecution(c);
            }
        } catch (Throwable t) {
            log.error("Unhandled error during abandoned execution recovery scan: {}", t.getMessage(), t);
        }
    }

    public void recoverExecution(WorkflowExecutionCoordination c) {
        Long workflowId = c.getWorkflowId();
        String workflowName = c.getWorkflowName();

        ExecutionCoordinationService.RecoveryResult result = coordinationService.reclaimForRecovery(
                workflowId,
                dispatcher.getInstanceId(),
                properties.getLeaseDurationSeconds(),
                properties.getMaxRecoveryAttempts()
        );

        if (result.outcome() == ExecutionCoordinationService.RecoveryOutcome.FAILED_EXCEEDED_ATTEMPTS) {
            log.error("Workflow {} (id={}) exceeded maximum recovery attempts. Emitting workflow failure event.",
                    workflowName, workflowId);
            Long gen = result.coordination() != null ? result.coordination().getGeneration() : c.getGeneration();
            executionEventProducer.publishWorkflowCompleted(
                    WorkflowCompletedEvent.builder()
                            .workflowId(workflowId)
                            .workflowName(workflowName)
                            .success(false)
                            .generation(gen)
                            .eventId("wf:" + workflowId + ":gen:" + gen + ":workflow-completed")
                            .build()
            );
        } else if (result.outcome() == ExecutionCoordinationService.RecoveryOutcome.RECLAIMED) {
            WorkflowExecutionCoordination reclaimed = result.coordination();
            if (reclaimed.getPayloadJson() != null) {
                try {
                    WorkflowExecutionRequestEvent event = objectMapper.readValue(
                            reclaimed.getPayloadJson(),
                            WorkflowExecutionRequestEvent.class
                    );
                    event.setExecutionMode("RECOVERY");
                    log.info("Redispatching recovered workflow {} (id={}, gen={}) to worker pool",
                            workflowName, workflowId, reclaimed.getGeneration());
                    dispatcher.dispatch(event, reclaimed.getGeneration());
                } catch (WorkerQueueFullException e) {
                    log.warn("Worker queue saturated during recovery dispatch for workflow {}; releasing lease for retry.",
                            workflowId);
                    coordinationService.releaseLeaseOnDispatchFailure(workflowId);
                } catch (Exception e) {
                    log.error("Failed to deserialize payload for recovered workflow {}: {}", workflowId, e.getMessage());
                }
            } else {
                log.warn("Abandoned workflow {} (id={}) has no stored payload; cannot auto-redispatch. Marking FAILED.",
                        workflowName, workflowId);
                coordinationService.markCompleted(workflowId, false);
                Long gen = reclaimed.getGeneration();
                executionEventProducer.publishWorkflowCompleted(
                        WorkflowCompletedEvent.builder()
                                .workflowId(workflowId)
                                .workflowName(workflowName)
                                .success(false)
                                .generation(gen)
                                .eventId("wf:" + workflowId + ":gen:" + gen + ":workflow-completed")
                                .build()
                );
            }
        }
    }
}
