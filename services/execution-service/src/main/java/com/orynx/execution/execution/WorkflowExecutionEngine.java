package com.orynx.execution.execution;

import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.event.dto.TaskCompletedEvent;
import com.orynx.execution.event.dto.TaskStartedEvent;
import com.orynx.execution.event.dto.WorkflowCompletedEvent;
import com.orynx.execution.event.dto.WorkflowExecutionPausedEvent;
import com.orynx.execution.execution.dto.TaskExecutionResult;
import com.orynx.execution.kafka.ExecutionEventProducer;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.execution.workflow.event.dto.TaskExecutionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WorkflowExecutionEngine {
    private final TaskExecutor taskExecutor;
    private final ExecutionEventProducer executionEventProducer;
    private final WorkflowCancellationRegistry cancellationRegistry;
    private final WorkflowPauseRegistry pauseRegistry;
    private final ExecutionCoordinationService coordinationService;
    private final ActiveExecutionTracker activeExecutionTracker;

    @Autowired
    public WorkflowExecutionEngine(
            TaskExecutor taskExecutor,
            ExecutionEventProducer executionEventProducer,
            WorkflowCancellationRegistry cancellationRegistry,
            WorkflowPauseRegistry pauseRegistry,
            ExecutionCoordinationService coordinationService,
            ActiveExecutionTracker activeExecutionTracker
    ) {
        this.taskExecutor = taskExecutor;
        this.executionEventProducer = executionEventProducer;
        this.cancellationRegistry = cancellationRegistry;
        this.pauseRegistry = pauseRegistry;
        this.coordinationService = coordinationService;
        this.activeExecutionTracker = activeExecutionTracker;
    }

    // Constructor for backwards-compatible testing with mocks
    public WorkflowExecutionEngine(
            TaskExecutor taskExecutor,
            ExecutionEventProducer executionEventProducer,
            WorkflowCancellationRegistry cancellationRegistry,
            WorkflowPauseRegistry pauseRegistry
    ) {
        this(taskExecutor, executionEventProducer, cancellationRegistry, pauseRegistry, null, null);
    }

    public void execute(WorkflowExecutionRequestEvent event) {

        log.info("========================================");
        log.info("WorkflowExecutionEngine Started");
        log.info("Workflow : {}", event.getWorkflowName());

        Long workflowId = event.getWorkflowId();
        Integer lastCompletedExecutionOrder = null;

        if (event.getTasks() != null) {
            for (TaskExecutionRequest task : event.getTasks()) {

                // Stop execution if workflow has been cancelled
                if (checkCancelled(workflowId)) {
                    handleCancellation(event);
                    return;
                }

                if (checkPaused(workflowId)) {
                    handlePause(event, lastCompletedExecutionOrder);
                    return;
                }

                executionEventProducer.publishTaskStarted(
                        TaskStartedEvent.builder()
                                .workflowId(workflowId)
                                .workflowName(event.getWorkflowName())
                                .taskName(task.getName())
                                .executionOrder(task.getExecutionOrder())
                                .build()
                );

                TaskExecutionResult result = taskExecutor.execute(task);

                executionEventProducer.publishTaskCompleted(
                        TaskCompletedEvent.builder()
                                .workflowId(workflowId)
                                .workflowName(event.getWorkflowName())
                                .taskName(task.getName())
                                .executionOrder(task.getExecutionOrder())
                                .success(result.isSuccess())
                                .attempts(result.getAttempts())
                                .build()
                );

                if (result.isSuccess()) {
                    lastCompletedExecutionOrder = task.getExecutionOrder();
                }

                // If cancelled while task was executing
                if (checkCancelled(workflowId)) {
                    handleCancellation(event);
                    return;
                }

                // If paused while task was executing
                if (checkPaused(workflowId)) {
                    handlePause(event, lastCompletedExecutionOrder);
                    return;
                }

                if (!result.isSuccess()) {
                    log.error(
                            "Workflow {} failed because task {} failed",
                            event.getWorkflowName(),
                            task.getName()
                    );

                    if (coordinationService != null) {
                        coordinationService.markCompleted(workflowId, false);
                    }

                    executionEventProducer.publishWorkflowCompleted(
                            WorkflowCompletedEvent.builder()
                                    .workflowId(workflowId)
                                    .workflowName(event.getWorkflowName())
                                    .success(false)
                                    .build()
                    );

                    return;
                }
            }
        }

        if (checkCancelled(workflowId)) {
            handleCancellation(event);
            return;
        }

        if (checkPaused(workflowId)) {
            handlePause(event, lastCompletedExecutionOrder);
            return;
        }

        if (coordinationService != null) {
            coordinationService.markCompleted(workflowId, true);
        }

        executionEventProducer.publishWorkflowCompleted(
                WorkflowCompletedEvent.builder()
                        .workflowId(workflowId)
                        .workflowName(event.getWorkflowName())
                        .success(true)
                        .build()
        );

        log.info(
                "Workflow {} completed successfully",
                event.getWorkflowName()
        );
    }

    private boolean checkCancelled(Long workflowId) {
        return cancellationRegistry.isCancelled(workflowId)
                || (activeExecutionTracker != null && activeExecutionTracker.isCancelled(workflowId))
                || (coordinationService != null && coordinationService.isCancelled(workflowId))
                || Thread.currentThread().isInterrupted();
    }

    private boolean checkPaused(Long workflowId) {
        return pauseRegistry.isPaused(workflowId)
                || (activeExecutionTracker != null && activeExecutionTracker.isPaused(workflowId))
                || (coordinationService != null && coordinationService.isPaused(workflowId));
    }

    private void handleCancellation(WorkflowExecutionRequestEvent event) {
        Long workflowId = event.getWorkflowId();
        log.warn("Workflow {} has been cancelled. Stopping execution.", event.getWorkflowName());
        if (coordinationService != null) {
            coordinationService.markCancelled(workflowId);
        }
        cancellationRegistry.clear(workflowId);
        Thread.interrupted(); // Clear interrupted status
    }

    private void handlePause(WorkflowExecutionRequestEvent event, Integer lastCompletedExecutionOrder) {
        Long workflowId = event.getWorkflowId();
        log.warn("Workflow {} is paused. Stopping execution.", event.getWorkflowName());
        if (coordinationService != null) {
            coordinationService.markPaused(workflowId, lastCompletedExecutionOrder);
        }
        executionEventProducer.publishWorkflowExecutionPaused(
                WorkflowExecutionPausedEvent.builder()
                        .workflowId(workflowId)
                        .workflowName(event.getWorkflowName())
                        .lastCompletedExecutionOrder(lastCompletedExecutionOrder)
                        .build()
        );
    }
}
