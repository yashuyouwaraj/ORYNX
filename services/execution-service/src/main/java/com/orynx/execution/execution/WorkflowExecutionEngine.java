package com.orynx.execution.execution;

import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.coordination.StaleWorkerExecutionException;
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
        execute(event, 1L, null);
    }

    public void execute(WorkflowExecutionRequestEvent event, Long generation, String instanceId) {
        Long workflowId = event.getWorkflowId();
        String workflowName = event.getWorkflowName();

        log.info("========================================");
        log.info("WorkflowExecutionEngine Started (gen={}, instance={})", generation, instanceId);
        log.info("Workflow : {}", workflowName);

        Integer lastCompletedExecutionOrder = null;

        if (event.getTasks() != null) {
            for (TaskExecutionRequest task : event.getTasks()) {

                // 1. Fencing check before task
                if (checkFenced(workflowId, generation, instanceId)) {
                    log.warn("Workflow {} (id={}, gen={}) is FENCED. Aborting execution without publishing events.",
                            workflowName, workflowId, generation);
                    return;
                }

                // 2. Cancellation check before task
                if (checkCancelled(workflowId)) {
                    handleCancellation(event, generation, instanceId);
                    return;
                }

                // 3. Pause check before task
                if (checkPaused(workflowId)) {
                    handlePause(event, lastCompletedExecutionOrder, generation, instanceId);
                    return;
                }

                // Publish task started only if not fenced
                if (!checkFenced(workflowId, generation, instanceId)) {
                    executionEventProducer.publishTaskStarted(
                            TaskStartedEvent.builder()
                                    .workflowId(workflowId)
                                    .workflowName(workflowName)
                                    .taskName(task.getName())
                                    .executionOrder(task.getExecutionOrder())
                                    .generation(generation)
                                    .eventId("wf:" + workflowId + ":gen:" + generation + ":task-started:" + task.getExecutionOrder())
                                    .build()
                    );
                } else {
                    return;
                }

                TaskExecutionResult result = taskExecutor.execute(task);

                // Check fencing right after task completes
                if (checkFenced(workflowId, generation, instanceId)) {
                    log.warn("Workflow {} (id={}, gen={}) fenced during task {}. Suppressing task completion.",
                            workflowName, workflowId, generation, task.getName());
                    return;
                }

                executionEventProducer.publishTaskCompleted(
                        TaskCompletedEvent.builder()
                                .workflowId(workflowId)
                                .workflowName(workflowName)
                                .taskName(task.getName())
                                .executionOrder(task.getExecutionOrder())
                                .success(result.isSuccess())
                                .attempts(result.getAttempts())
                                .generation(generation)
                                .eventId("wf:" + workflowId + ":gen:" + generation + ":task-completed:" + task.getExecutionOrder())
                                .build()
                );

                if (result.isSuccess()) {
                    lastCompletedExecutionOrder = task.getExecutionOrder();
                }

                // If cancelled while task was executing
                if (checkCancelled(workflowId)) {
                    handleCancellation(event, generation, instanceId);
                    return;
                }

                // If paused while task was executing
                if (checkPaused(workflowId)) {
                    handlePause(event, lastCompletedExecutionOrder, generation, instanceId);
                    return;
                }

                if (!result.isSuccess()) {
                    log.error(
                            "Workflow {} failed because task {} failed",
                            workflowName,
                            task.getName()
                    );

                    if (!checkFenced(workflowId, generation, instanceId)) {
                        try {
                            if (coordinationService != null) {
                                if (generation != null && instanceId != null) {
                                    coordinationService.markCompleted(workflowId, generation, instanceId, false);
                                } else {
                                    coordinationService.markCompleted(workflowId, false);
                                }
                            }
                        } catch (StaleWorkerExecutionException e) {
                            log.warn("Fenced worker failed to mark failure for workflow {}: {}", workflowId, e.getMessage());
                            return;
                        }

                        executionEventProducer.publishWorkflowCompleted(
                                WorkflowCompletedEvent.builder()
                                        .workflowId(workflowId)
                                        .workflowName(workflowName)
                                        .success(false)
                                        .generation(generation)
                                        .eventId("wf:" + workflowId + ":gen:" + generation + ":workflow-completed")
                                        .build()
                        );
                    }

                    return;
                }
            }
        }

        // Final checks before completion
        if (checkFenced(workflowId, generation, instanceId)) {
            log.warn("Workflow {} (id={}, gen={}) fenced before completion. Suppressing completion.",
                    workflowName, workflowId, generation);
            return;
        }

        if (checkCancelled(workflowId)) {
            handleCancellation(event, generation, instanceId);
            return;
        }

        if (checkPaused(workflowId)) {
            handlePause(event, lastCompletedExecutionOrder, generation, instanceId);
            return;
        }

        try {
            if (coordinationService != null) {
                if (generation != null && instanceId != null) {
                    coordinationService.markCompleted(workflowId, generation, instanceId, true);
                } else {
                    coordinationService.markCompleted(workflowId, true);
                }
            }
        } catch (StaleWorkerExecutionException e) {
            log.warn("Fenced worker attempted to mark completion for workflow {}. Suppressing event.", workflowId);
            return;
        }

        executionEventProducer.publishWorkflowCompleted(
                WorkflowCompletedEvent.builder()
                        .workflowId(workflowId)
                        .workflowName(workflowName)
                        .success(true)
                        .generation(generation)
                        .eventId("wf:" + workflowId + ":gen:" + generation + ":workflow-completed")
                        .build()
        );

        log.info(
                "Workflow {} completed successfully (gen={})",
                workflowName,
                generation
        );
    }

    private boolean checkFenced(Long workflowId, Long generation, String instanceId) {
        if (activeExecutionTracker != null && activeExecutionTracker.isFenced(workflowId, generation)) {
            return true;
        }
        if (coordinationService != null && generation != null && instanceId != null) {
            return coordinationService.isFenced(workflowId, generation, instanceId);
        }
        return false;
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

    private void handleCancellation(WorkflowExecutionRequestEvent event, Long generation, String instanceId) {
        Long workflowId = event.getWorkflowId();
        log.warn("Workflow {} has been cancelled. Stopping execution.", event.getWorkflowName());
        if (coordinationService != null) {
            coordinationService.markCancelled(workflowId);
        }
        cancellationRegistry.clear(workflowId);
        Thread.interrupted(); // Clear interrupted status
    }

    private void handlePause(WorkflowExecutionRequestEvent event, Integer lastCompletedExecutionOrder, Long generation, String instanceId) {
        Long workflowId = event.getWorkflowId();
        log.warn("Workflow {} is paused. Stopping execution.", event.getWorkflowName());
        if (coordinationService != null) {
            try {
                if (generation != null && instanceId != null) {
                    coordinationService.markPaused(workflowId, generation, instanceId, lastCompletedExecutionOrder);
                } else {
                    coordinationService.markPaused(workflowId, lastCompletedExecutionOrder);
                }
            } catch (StaleWorkerExecutionException e) {
                log.warn("Fenced worker attempted to record pause for workflow {}: {}", workflowId, e.getMessage());
                return;
            }
        }
        executionEventProducer.publishWorkflowExecutionPaused(
                WorkflowExecutionPausedEvent.builder()
                        .workflowId(workflowId)
                        .workflowName(event.getWorkflowName())
                        .lastCompletedExecutionOrder(lastCompletedExecutionOrder)
                        .generation(generation)
                        .eventId("wf:" + workflowId + ":gen:" + generation + ":workflow-paused:" +
                                (lastCompletedExecutionOrder != null ? lastCompletedExecutionOrder : 0))
                        .build()
        );
    }
}
