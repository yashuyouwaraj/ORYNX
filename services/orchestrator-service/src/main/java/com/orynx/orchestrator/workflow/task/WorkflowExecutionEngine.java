package com.orynx.orchestrator.workflow.task;

import com.orynx.orchestrator.workflow.*;
import com.orynx.orchestrator.workflow.dto.WorkflowRealtimeEvent;
import com.orynx.orchestrator.workflow.task.dto.TaskExecutionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowExecutionEngine {

    private final WorkflowTaskRepository workflowTaskRepository;
    private final TaskEventProducer taskEventProducer;
    private final WorkflowRepository workflowRepository;
    private final WorkflowEventProducer workflowEventProducer;

    public void executeWorkflow(Long workflowId) {

        List<WorkflowTask> tasks =
                workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(workflowId);

        Workflow workflow =
                workflowRepository.findById(workflowId)
                        .orElseThrow(() -> new RuntimeException("Workflow not found"));

        workflow.setStartedAt(System.currentTimeMillis());
        workflowRepository.save(workflow);

        if (tasks.isEmpty()) {
            log.warn("No tasks found for workflow {}", workflowId);
            return;
        }

        for (WorkflowTask task : tasks) {

            log.info("Executing task {}", task.getName());

            task.setStartedAt(System.currentTimeMillis());

            while (task.getRetryCount() < task.getMaxRetries()) {

                task.setStatus(TaskStatus.RUNNING);

                workflowTaskRepository.save(task);

                taskEventProducer.publishTaskEvent(
                        TaskExecutionEvent.builder()
                                .workflowId(workflowId)
                                .taskId(task.getId())
                                .taskName(task.getName())
                                .status("RUNNING")
                                .build()
                );

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                boolean success = Math.random() > 0.4;

                if (success) {

                    task.setStatus(TaskStatus.COMPLETED);
                    task.setCompletedAt(System.currentTimeMillis());

                    workflowTaskRepository.save(task);

                    taskEventProducer.publishTaskEvent(
                            TaskExecutionEvent.builder()
                                    .workflowId(workflowId)
                                    .taskId(task.getId())
                                    .taskName(task.getName())
                                    .status("COMPLETED")
                                    .build()
                    );

                    log.info("Completed task {}", task.getName());

                    break;
                }

                task.setRetryCount(task.getRetryCount() + 1);

                task.setStatus(TaskStatus.RETRYING);

                workflowTaskRepository.save(task);

                taskEventProducer.publishTaskEvent(
                        TaskExecutionEvent.builder()
                                .workflowId(workflowId)
                                .taskId(task.getId())
                                .taskName(task.getName())
                                .status("RETRYING")
                                .build()
                );

                log.warn(
                        "Retry {} for task {}",
                        task.getRetryCount(),
                        task.getName()
                );
            }

            if (task.getStatus() != TaskStatus.COMPLETED) {

                task.setStatus(TaskStatus.FAILED);

                workflowTaskRepository.save(task);

                taskEventProducer.publishTaskEvent(
                        TaskExecutionEvent.builder()
                                .workflowId(workflowId)
                                .taskId(task.getId())
                                .taskName(task.getName())
                                .status("FAILED")
                                .build()
                );

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

                log.error("Workflow failed because task {} failed", task.getName());

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
    }
}