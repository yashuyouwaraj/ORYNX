package com.orynx.orchestrator.workflow.task;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.orynx.orchestrator.workflow.task.dto.TaskExecutionEvent;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class TaskEventConsumer {

    private final SimpMessagingTemplate messagingTemplate;

    public TaskEventConsumer(
            SimpMessagingTemplate messagingTemplate
    ) {

        this.messagingTemplate
                = messagingTemplate;
    }

    @KafkaListener(
            topics = TaskEventProducer.TASK_EXECUTION_EVENTS_TOPIC,
            groupId = "orynx-task-group"
    )
    public void consume(
            TaskExecutionEvent event
    ) {

        log.info(
                "Task Event Received: {}",
                event.getTaskName()
        );

        messagingTemplate.convertAndSend(
                "/topic/tasks",
                event
        );
    }
}
