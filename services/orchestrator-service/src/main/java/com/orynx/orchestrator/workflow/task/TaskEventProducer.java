package com.orynx.orchestrator.workflow.task;

import com.orynx.orchestrator.workflow.task.dto.TaskExecutionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskEventProducer {
    public static final String TASK_EXECUTION_EVENTS_TOPIC = "task-execution-events";

    private final KafkaTemplate<String, TaskExecutionEvent> kafkaTemplate;

    public void publishTaskEvent(TaskExecutionEvent event) {
        try {
            kafkaTemplate.send(TASK_EXECUTION_EVENTS_TOPIC, event);
        } catch (Exception ex) {
            log.warn("Failed to publish task event for task {} — continuing execution", event.getTaskId(), ex);
        }
    }
}
