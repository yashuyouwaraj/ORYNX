package com.orynx.execution.execution;

import com.orynx.execution.event.dto.TaskCompletedEvent;
import com.orynx.execution.event.dto.TaskStartedEvent;
import com.orynx.execution.event.dto.WorkflowCompletedEvent;
import com.orynx.execution.event.dto.WorkflowExecutionPausedEvent;
import com.orynx.execution.execution.dto.TaskExecutionResult;
import com.orynx.execution.kafka.ExecutionEventProducer;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.execution.workflow.event.dto.TaskExecutionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowExecutionEngine {
    private final TaskExecutor taskExecutor;
    private final ExecutionEventProducer executionEventProducer;
    private final WorkflowCancellationRegistry cancellationRegistry;
    private final WorkflowPauseRegistry pauseRegistry;

    public void execute(WorkflowExecutionRequestEvent event) {

        log.info("========================================");
        log.info("WorkflowExecutionEngine Started");
        log.info("Workflow : {}", event.getWorkflowName());

        Integer lastCompletedExecutionOrder = null;
        for (TaskExecutionRequest task : event.getTasks()) {

            // Stop execution if workflow has been cancelled
            if (cancellationRegistry.isCancelled(event.getWorkflowId())) {

                log.warn(
                        "Workflow {} has been cancelled. Stopping execution.",
                        event.getWorkflowName()
                );

                cancellationRegistry.clear(event.getWorkflowId());

                return;
            }

            if (pauseRegistry.isPaused(event.getWorkflowId())) {

                log.warn(
                        "Workflow {} is paused. Stopping execution.",
                        event.getWorkflowName()
                );

                executionEventProducer.publishWorkflowExecutionPaused(
                        WorkflowExecutionPausedEvent.builder()
                                .workflowId(event.getWorkflowId())
                                .workflowName(event.getWorkflowName())
                                .lastCompletedExecutionOrder(
                                        lastCompletedExecutionOrder
                                )
                                .build()
                );

                return;
            }

            executionEventProducer.publishTaskStarted(
                    TaskStartedEvent.builder()
                            .workflowId(event.getWorkflowId())
                            .workflowName(event.getWorkflowName())
                            .taskName(task.getName())
                            .executionOrder(task.getExecutionOrder())
                            .build()
            );

            TaskExecutionResult result = taskExecutor.execute(task);

            executionEventProducer.publishTaskCompleted(
                    TaskCompletedEvent.builder()
                            .workflowId(event.getWorkflowId())
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

            if (!result.isSuccess()) {

                log.error(
                        "Workflow {} failed because task {} failed",
                        event.getWorkflowName(),
                        task.getName()
                );

                executionEventProducer.publishWorkflowCompleted(
                        WorkflowCompletedEvent.builder()
                                .workflowId(event.getWorkflowId())
                                .workflowName(event.getWorkflowName())
                                .success(false)
                                .build()
                );

                return;
            }
        }

        executionEventProducer.publishWorkflowCompleted(
                WorkflowCompletedEvent.builder()
                        .workflowId(event.getWorkflowId())
                        .workflowName(event.getWorkflowName())
                        .success(true)
                        .build()
        );

        log.info(
                "Workflow {} completed successfully",
                event.getWorkflowName()
        );
    }
}
