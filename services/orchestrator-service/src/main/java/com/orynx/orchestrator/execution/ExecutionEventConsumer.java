package com.orynx.orchestrator.execution;

import com.orynx.orchestrator.execution.event.TaskCompletedEvent;
import com.orynx.orchestrator.execution.event.TaskStartedEvent;
import com.orynx.orchestrator.execution.event.WorkflowCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ExecutionEventConsumer {
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
    }

    @KafkaListener(
            topics = "workflow-completed",
            groupId = "orchestrator-service",
            properties = {
                    "spring.json.value.default.type=com.orynx.orchestrator.execution.event.WorkflowCompletedEvent",
                    "spring.json.use.type.headers=false"
            }
    )
    public void consumeWorkflowCompleted(WorkflowCompletedEvent event){
        log.info(
                "Workflow Completed -> {}",
                event.getWorkflowName()
        );
    }
}
