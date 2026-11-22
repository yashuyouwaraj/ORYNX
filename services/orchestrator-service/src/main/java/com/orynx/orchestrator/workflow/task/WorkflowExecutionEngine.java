package com.orynx.orchestrator.workflow.task;

import com.orynx.orchestrator.execution.WorkflowExecution;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.workflow.*;
import com.orynx.orchestrator.workflow.dto.WorkflowRealtimeEvent;
import com.orynx.orchestrator.workflow.task.dto.TaskExecutionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class WorkflowExecutionEngine {

    private final WorkflowTaskRepository workflowTaskRepository;
    private final TaskEventProducer taskEventProducer;
    private final WorkflowRepository workflowRepository;
    private final WorkflowEventProducer workflowEventProducer;
    private final WorkflowExecutionRepository workflowExecutionRepository;

    public WorkflowExecutionEngine(
            WorkflowTaskRepository workflowTaskRepository,
            TaskEventProducer taskEventProducer,
            WorkflowRepository workflowRepository,
            WorkflowEventProducer workflowEventProducer
    ) {
        this(workflowTaskRepository, taskEventProducer, workflowRepository, workflowEventProducer, null);
    }

    @Autowired
    public WorkflowExecutionEngine(
            WorkflowTaskRepository workflowTaskRepository,
            TaskEventProducer taskEventProducer,
            WorkflowRepository workflowRepository,
            WorkflowEventProducer workflowEventProducer,
            WorkflowExecutionRepository workflowExecutionRepository
    ) {
        this.workflowTaskRepository = workflowTaskRepository;
        this.taskEventProducer = taskEventProducer;
        this.workflowRepository = workflowRepository;
        this.workflowEventProducer = workflowEventProducer;
        this.workflowExecutionRepository = workflowExecutionRepository;
    }

    @Async("workflowExecutor")
    public void executeWorkflowAsync(Long workflowId) {
        try {
            executeWorkflow(workflowId);
        } catch (Exception ex) {
            log.error("Async workflow execution failed for id={}", workflowId, ex);
        }
    }

    public void executeWorkflow(Long workflowId) {
        Workflow workflow =
                workflowRepository.findById(workflowId)
                        .orElseThrow(() -> new RuntimeException("Workflow not found"));

        workflow.setStatus(WorkflowStatus.RUNNING);
        workflow.setStartedAt(System.currentTimeMillis());
        workflowRepository.save(workflow);

        try {
            List<WorkflowTask> tasks =
                    workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(workflowId);

            if (tasks.isEmpty()) {
                log.warn("No tasks found for workflow {}", workflowId);
                markWorkflowFailed(workflow, "No tasks found");
                return;
            }

            for (WorkflowTask task : tasks) {
                executeTask(workflow, workflowId, task);
                if (workflow.getStatus() == WorkflowStatus.FAILED) {
                    return;
                }
            }

            workflow.setStatus(WorkflowStatus.COMPLETED);
            workflow.setCompletedAt(System.currentTimeMillis());
            workflowRepository.save(workflow);

            workflowEventProducer.publishWorkflowEvent(
                    WorkflowRealtimeEvent.builder()
                            .workflowId(workflow.getId())
                            .workflowName(workflow.getName())
                            .goal(workflow.getGoal())
                            .status("COMPLETED")
                            .build()
            );

            log.info("Workflow completed {}", workflow.getName());
        } catch (Exception ex) {
            markWorkflowFailed(workflow, ex.getMessage());
            throw ex;
        }
    }

    private void executeTask(Workflow workflow, Long workflowId, WorkflowTask task) {
        log.info("Executing task {}", task.getName());

        Long generation = resolveGeneration(workflowId);
        task.setStartedAt(System.currentTimeMillis());
        task.setRetryCount(0);

        int maxAttempts = task.getMaxRetries() + 1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.info(
                    "Task {} attempt {}/{} (maxRetries={})",
                    task.getName(),
                    attempt,
                    maxAttempts,
                    task.getMaxRetries()
            );

            task.setStatus(TaskStatus.RUNNING);
            workflowTaskRepository.save(task);

            taskEventProducer.publishTaskEvent(
                    TaskExecutionEvent.builder()
                            .workflowId(workflowId)
                            .taskId(task.getId())
                            .taskName(task.getName())
                            .status("RUNNING")
                            .generation(generation)
                            .eventId(buildTaskEventId(workflowId, generation, task.getId(), attempt, "RUNNING"))
                            .build()
            );

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markWorkflowFailed(workflow, "Execution interrupted");
                return;
            }

            boolean success = simulateTaskSuccess(attempt, maxAttempts);

            if (success) {
                task.setStatus(TaskStatus.COMPLETED);
                task.setCompletedAt(System.currentTimeMillis());
                task.setRetryCount(attempt - 1);
                workflowTaskRepository.save(task);

                taskEventProducer.publishTaskEvent(
                        TaskExecutionEvent.builder()
                                .workflowId(workflowId)
                                .taskId(task.getId())
                                .taskName(task.getName())
                                .status("COMPLETED")
                                .generation(generation)
                                .eventId(buildTaskEventId(workflowId, generation, task.getId(), attempt, "COMPLETED"))
                                .build()
                );

                log.info("Completed task {} on attempt {}", task.getName(), attempt);
                return;
            }

            task.setRetryCount(attempt);
            task.setStatus(TaskStatus.RETRYING);
            workflowTaskRepository.save(task);

            taskEventProducer.publishTaskEvent(
                    TaskExecutionEvent.builder()
                            .workflowId(workflowId)
                            .taskId(task.getId())
                            .taskName(task.getName())
                            .status("RETRYING")
                            .generation(generation)
                            .eventId(buildTaskEventId(workflowId, generation, task.getId(), attempt, "RETRYING"))
                            .build()
            );

            log.warn("Retry {} for task {} (attempt {} failed)", task.getRetryCount(), task.getName(), attempt);
        }

        task.setStatus(TaskStatus.FAILED);
        workflowTaskRepository.save(task);

        taskEventProducer.publishTaskEvent(
                TaskExecutionEvent.builder()
                        .workflowId(workflowId)
                        .taskId(task.getId())
                        .taskName(task.getName())
                        .status("FAILED")
                        .generation(generation)
                        .eventId(buildTaskEventId(workflowId, generation, task.getId(), maxAttempts, "FAILED"))
                        .build()
        );

        markWorkflowFailed(workflow, "Task failed after " + maxAttempts + " attempts: " + task.getName());
        log.error("Workflow failed because task {} exhausted all retries", task.getName());
    }

    private Long resolveGeneration(Long workflowId) {
        if (workflowExecutionRepository == null || workflowId == null) {
            return 1L;
        }
        return workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(workflowId)
                .map(WorkflowExecution::getExecutionGeneration)
                .orElse(1L);
    }

    private String buildTaskEventId(Long workflowId, Long generation, Long taskId, int attempt, String status) {
        return "wf:" + workflowId + ":gen:" + generation + ":task:" + taskId + ":attempt:" + attempt + ":" + status;
    }

    private boolean simulateTaskSuccess(int attempt, int maxAttempts) {
        if (attempt == maxAttempts) {
            return true;
        }
        return Math.random() > 0.4;
    }

    private void markWorkflowFailed(Workflow workflow, String reason) {
        workflow.setStatus(WorkflowStatus.FAILED);
        workflow.setCompletedAt(System.currentTimeMillis());
        workflowRepository.save(workflow);

        workflowEventProducer.publishWorkflowEvent(
                WorkflowRealtimeEvent.builder()
                        .workflowId(workflow.getId())
                        .workflowName(workflow.getName())
                        .goal(workflow.getGoal())
                        .status("FAILED")
                        .build()
        );

        log.error("Workflow {} failed: {}", workflow.getName(), reason);
    }
}
