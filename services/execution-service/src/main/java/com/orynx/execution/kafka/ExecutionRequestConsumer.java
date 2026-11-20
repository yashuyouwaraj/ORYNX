package com.orynx.execution.kafka;

import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.coordination.ExecutionLeaseAcquisitionResult;
import com.orynx.execution.execution.ExecutionService;
import com.orynx.execution.execution.WorkflowExecutionDispatcher;
import com.orynx.execution.worker.ExecutionWorkerProperties;
import com.orynx.execution.worker.WorkerQueueFullException;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ExecutionRequestConsumer {

    private final ExecutionService executionService;
    private final ExecutionCoordinationService coordinationService;
    private final WorkflowExecutionDispatcher dispatcher;
    private final ExecutionWorkerProperties properties;

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

        log.info("========================================");
        log.info("Received Workflow Execution Request");
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

        // Distributed duplicate execution & lease acquisition check
        ExecutionLeaseAcquisitionResult leaseResult = coordinationService.tryAcquireLease(
                workflowId,
                workflowName,
                dispatcher.getInstanceId(),
                properties.getLeaseDurationSeconds()
        );

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

        try {
            // Dispatch to managed worker pool (non-blocking hand-off)
            executionService.executeWorkflow(event);

            // Acknowledge Kafka message immediately upon safe dispatch into worker queue
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
            log.info("Workflow {} (id={}) successfully handed off to worker pool. Kafka offset acknowledged.", workflowName, workflowId);
        } catch (WorkerQueueFullException e) {
            log.error(
                    "Worker pool capacity exhausted for workflow {} (id={}). Releasing lease and backpressuring Kafka.",
                    workflowName,
                    workflowId
            );
            coordinationService.releaseLeaseOnDispatchFailure(workflowId);
            // Do NOT acknowledge; throw exception so Kafka triggers backpressure and redelivery
            throw e;
        } catch (Exception e) {
            log.error("Failed to dispatch workflow {} (id={}) to worker pool. Releasing lease.", workflowName, workflowId, e);
            coordinationService.releaseLeaseOnDispatchFailure(workflowId);
            throw e;
        }
    }
}
