package com.orynx.orchestrator.workflow.task;

import com.orynx.orchestrator.workflow.task.dto.TaskExecutionEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskEventProducer {
    public static final String TASK_EXECUTION_EVENTS_TOPIC = "task-execution-events";

    private final KafkaTemplate<String, TaskExecutionEvent> kafkaTemplate;

    public void  publishTaskEvent(TaskExecutionEvent event){
        kafkaTemplate.send(TASK_EXECUTION_EVENTS_TOPIC, event);
    }
}
