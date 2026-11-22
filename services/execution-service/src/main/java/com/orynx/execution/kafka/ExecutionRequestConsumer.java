package com.orynx.execution.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.coordination.ExecutionLeaseAcquisitionResult;
import com.orynx.execution.execution.ActiveExecutionTracker;
import com.orynx.execution.execution.ExecutionService;
import com.orynx.execution.execution.WorkflowExecutionDispatcher;
import com.orynx.execution.execution.WorkflowPauseRegistry;
import com.orynx.execution.worker.ExecutionWorkerProperties;
import com.orynx.execution.worker.WorkerQueueFullException;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ExecutionRequestConsumer {

    private final ExecutionService executionService;
    private final ExecutionCoordinationService coordinationService;
    private final WorkflowExecutionDispatcher dispatcher;
    private final ExecutionWorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final WorkflowPauseRegistry pauseRegistry;
    private final ActiveExecutionTracker activeExecutionTracker;

    public ExecutionRequestConsumer(
            ExecutionService executionService,
            ExecutionCoordinationService coordinationService,
            WorkflowExecutionDispatcher dispatcher,
            ExecutionWorkerProperties properties,
            ObjectMapper objectMapper
    ) {
        this(executionService, coordinationService, dispatcher, properties, objectMapper, null, null);
    }

    @Autowired
    public ExecutionRequestConsumer(
            ExecutionService executionService,
            ExecutionCoordinationService coordinationService,
            WorkflowExecutionDispatcher dispatcher,
            ExecutionWorkerProperties properties,
            ObjectMapper objectMapper,
            WorkflowPauseRegistry pauseRegistry,
            ActiveExecutionTracker activeExecutionTracker
    ) {
        this.executionService = executionService;
        this.coordinationService = coordinationService;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.pauseRegistry = pauseRegistry;
        this.activeExecutionTracker = activeExecutionTracker;
    }

    @KafkaListener(topics = "workflow-execution", groupId = "execution-service")
    public void consume(WorkflowExecutionRequestEvent event, Acknowledgment acknowledgment) {
        if (event == null || event.getWorkflowId() == null) {
            log.warn("Received malformed or null WorkflowExecutionRequestEvent. Discarding.");
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
            return;
        }

        Long workflowId = event.getWorkflowId();
        String workflowName = event.getWorkflowName();
        boolean isResume = Boolean.TRUE.equals(event.getResume());

        log.info("========================================");
        log.info("Received Workflow Execution Request (eventId={}, resume={})", event.getEventId(), isResume);
        log.info("Workflow Id   : {}", workflowId);
        log.info("Workflow Name : {}", workflowName);

        if (event.getTasks() != null) {
            log.info("Tasks: count={}", event.getTasks().size());
            event.getTasks().forEach(task ->
                    log.info(
                            "   {}. {} (Retries={})",
                            task.getExecutionOrder(),
                            task.getName(),
                            task.getMaxRetries()
                    )
            );
        }

        String payloadJson = null;
        try {
            payloadJson = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("Could not serialize execution payload for recovery: {}", e.getMessage());
        }

        // Distributed duplicate execution & lease acquisition check
        ExecutionLeaseAcquisitionResult leaseResult;
        if (event.getGeneration() != null) {
            leaseResult = coordinationService.tryAcquireLease(
                    workflowId,
                    workflowName,
                    dispatcher.getInstanceId(),
                    properties.getLeaseDurationSeconds(),
                    payloadJson,
                    isResume,
                    event.getGeneration()
            );
        } else {
            leaseResult = isResume
                    ? coordinationService.tryAcquireLease(
                            workflowId,
                            workflowName,
                            dispatcher.getInstanceId(),
                            properties.getLeaseDurationSeconds(),
                            payloadJson,
                            true
                    )
                    : coordinationService.tryAcquireLease(
                            workflowId,
                            workflowName,
                            dispatcher.getInstanceId(),
                            properties.getLeaseDurationSeconds(),
                            payloadJson
                    );
        }

        if (!leaseResult.isAcquired()) {
            log.warn(
                    "Execution request for workflow {} (id={}) rejected by coordination guard: {}. Acknowledging to discard duplicate.",
                    workflowName,
                    workflowId,
                    leaseResult.getMessage()
            );
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
            return;
        }

        if (isResume) {
            if (pauseRegistry != null) {
                pauseRegistry.clear(workflowId);
            }
            if (activeExecutionTracker != null) {
                activeExecutionTracker.clearPause(workflowId);
            }
        }

        try {
            // Dispatch to managed worker pool (non-blocking hand-off) with fencing generation
            executionService.executeWorkflow(event, leaseResult.getGeneration());

            // Acknowledge Kafka message immediately upon safe dispatch into worker queue
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
            log.info("Workflow {} (id={}, gen={}) successfully handed off to worker pool. Kafka offset acknowledged.",
                    workflowName, workflowId, leaseResult.getGeneration());
        } catch (WorkerQueueFullException e) {
            log.error(
                    "Worker pool capacity exhausted for workflow {} (id={}). Releasing lease and backpressuring Kafka.",
                    workflowName,
                    workflowId
            );
            try {
                coordinationService.releaseLeaseOnDispatchFailure(workflowId);
            } catch (Exception ex) {
                log.warn("Failed to release lease on dispatch failure for workflow {}: {}", workflowId, ex.getMessage());
            }
            // Do NOT acknowledge; throw exception so Kafka triggers backpressure and redelivery
            throw e;
        } catch (Exception e) {
            log.error("Failed to dispatch workflow {} (id={}) to worker pool. Releasing lease.", workflowName, workflowId, e);
            try {
                coordinationService.releaseLeaseOnDispatchFailure(workflowId);
            } catch (Exception ex) {
                log.warn("Failed to release lease on dispatch failure for workflow {}: {}", workflowId, ex.getMessage());
            }
            throw e;
        }
    }
}
