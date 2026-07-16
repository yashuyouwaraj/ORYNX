package com.orynx.execution.kafka;

import com.orynx.execution.event.dto.TaskCompletedEvent;
import com.orynx.execution.event.dto.TaskStartedEvent;
import com.orynx.execution.event.dto.WorkflowCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutionEventProducer {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishTaskStarted(TaskStartedEvent event){
        kafkaTemplate.send("task-started",event);
        log.info(
                "Published TaskStartedEvent for {}",
                event.getTaskName()
        );
    }

    public void publishTaskCompleted(TaskCompletedEvent event){
        kafkaTemplate.send("task-completed",event);

        log.info(
                "Published TaskCompletedEvent for {}",
                event.getTaskName()
        );
    }

    public void publishWorkflowCompleted(WorkflowCompletedEvent event){
        kafkaTemplate.send("workflow-completed",event);

        log.info(
                "Published WorkflowCompletedEvent for {}",
                event.getWorkflowName()
        );
    }
}
