package com.orynx.orchestrator.workflow.task;

import com.orynx.orchestrator.workflow.event.ExecutionEvent;
import com.orynx.orchestrator.workflow.event.ExecutionEventRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.orynx.orchestrator.workflow.task.dto.TaskExecutionEvent;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class TaskEventConsumer {

    private final SimpMessagingTemplate messagingTemplate;
    private final ExecutionEventRepository executionEventRepository;

    public TaskEventConsumer(
            SimpMessagingTemplate messagingTemplate,
            ExecutionEventRepository executionEventRepository
    ) {

        this.messagingTemplate
                = messagingTemplate;

        this.executionEventRepository=executionEventRepository;
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

        ExecutionEvent executionEvent = ExecutionEvent.builder()
                .workflowId(event.getWorkflowId())
                .taskId(event.getTaskId())
                .taskName(event.getTaskName())
                .status(event.getStatus())
                .timestamp(System.currentTimeMillis())
                .build();

        executionEventRepository.save(executionEvent);

        messagingTemplate.convertAndSend(
                "/topic/tasks",
                event
        );
    }
}
