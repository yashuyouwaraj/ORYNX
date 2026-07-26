package com.orynx.orchestrator.execution;

import com.orynx.orchestrator.execution.event.TaskCompletedEvent;
import com.orynx.orchestrator.execution.event.TaskStartedEvent;
import com.orynx.orchestrator.execution.event.WorkflowCompletedEvent;
import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowRepository;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class ExecutionEventConsumer {
    private final WorkflowRepository workflowRepository;
    private final WorkflowTaskRepository workflowTaskRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;

    @KafkaListener(
            topics = "task-started",
            groupId = "orchestrator-service",
            properties = {
                    "spring.json.value.default.type=com.orynx.orchestrator.execution.event.TaskStartedEvent",
                    "spring.json.use.type.headers=false"
            }
    )
    public void consumeTaskStarted(TaskStartedEvent event){
        log.info("Task Started -> Workflow: {}, Task: {}", event.getWorkflowName(),event.getTaskName());

        Optional<WorkflowTask> taskOptional  = workflowTaskRepository.findByWorkflowIdAndExecutionOrder(
                event.getWorkflowId(),
                event.getExecutionOrder()
        );

        if (taskOptional.isPresent()) {

            WorkflowTask task = taskOptional.get();

            task.setStatus(TaskStatus.RUNNING);
            task.setStartedAt(System.currentTimeMillis());

            workflowTaskRepository.save(task);

            log.info(
                    "Task {} marked RUNNING",
                    task.getName()
            );

        } else {

            log.warn(
                    "Task not found for workflow {} order {}",
                    event.getWorkflowId(),
                    event.getExecutionOrder()
            );
        }
    }

    @KafkaListener(
            topics = "task-completed",
            groupId = "orchestrator-service",
            properties = {
                    "spring.json.value.default.type=com.orynx.orchestrator.execution.event.TaskCompletedEvent",
                    "spring.json.use.type.headers=false"
            }
    )
    public void consumeTaskCompleted(TaskCompletedEvent event){
        log.info(
                "Task Completed -> Workflow: {}, Task: {}, Success: {}",
                event.getWorkflowName(),
                event.getTaskName(),
                event.isSuccess()
        );

        Optional<WorkflowTask> taskOptional = workflowTaskRepository.findByWorkflowIdAndExecutionOrder(
                event.getWorkflowId(),
                event.getExecutionOrder()
        );

        if(taskOptional.isPresent()){
          WorkflowTask task = taskOptional.get();

          task.setStatus(
                  event.isSuccess() ? TaskStatus.COMPLETED : TaskStatus.FAILED
          );

          task.setCompletedAt(System.currentTimeMillis());

          workflowTaskRepository.save(task);

            log.info(
                    "Task {} marked {}",
                    task.getName(),
                    task.getStatus()
            );
        }
        else{
            log.warn(
                    "Task not found for workflow {} order {}",
                    event.getWorkflowId(),
                    event.getExecutionOrder()
            );
        }
    }

    @KafkaListener(
            topics = "workflow-completed",
            groupId = "orchestrator-service",
            properties = {
                    "spring.json.value.default.type=com.orynx.orchestrator.execution.event.WorkflowCompletedEvent",
                    "spring.json.use.type.headers=false"
            }
    )

    public void consumeWorkflowCompleted(WorkflowCompletedEvent event) {

        log.info(
                "Workflow Completed -> {}",
                event.getWorkflowName()
        );

        Optional<Workflow> workflowOptional =
                workflowRepository.findById(event.getWorkflowId());

        if (workflowOptional.isPresent()) {

            Workflow workflow = workflowOptional.get();

            workflow.setStatus(
                    event.isSuccess()
                            ? WorkflowStatus.COMPLETED
                            : WorkflowStatus.FAILED
            );

            workflow.setCompletedAt(System.currentTimeMillis());

            workflowRepository.save(workflow);

            Optional<WorkflowExecution> executionOptional =
                    workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(
                            workflow.getId()
                    );

            if (executionOptional.isPresent()) {

                WorkflowExecution execution = executionOptional.get();

                execution.setStatus(workflow.getStatus());
                execution.setCompletedAt(System.currentTimeMillis());

                workflowExecutionRepository.save(execution);

                log.info(
                        "Execution history updated for workflow {}",
                        workflow.getName()
                );
            }

            log.info(
                    "Workflow {} marked {}",
                    workflow.getName(),
                    workflow.getStatus()
            );

        } else {

            log.warn(
                    "Workflow {} not found",
                    event.getWorkflowId()
            );

        }
    }
}
