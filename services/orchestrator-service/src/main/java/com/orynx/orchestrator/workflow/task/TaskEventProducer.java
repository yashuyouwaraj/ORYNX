package com.orynx.orchestrator.workflow.task;

import com.orynx.orchestrator.workflow.task.dto.TaskExecutionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskEventProducer {
    public static final String TASK_EXECUTION_EVENTS_TOPIC = "task-execution-events";

    private final KafkaTemplate<String, TaskExecutionEvent> kafkaTemplate;

    public void publishTaskEvent(TaskExecutionEvent event) {
        if (event == null) {
            return;
        }
        String key = event.getWorkflowId() != null
                ? String.valueOf(event.getWorkflowId())
                : (event.getTaskId() != null ? String.valueOf(event.getTaskId()) : "unknown");
        try {
            CompletableFuture<SendResult<String, TaskExecutionEvent>> future =
                    kafkaTemplate.send(TASK_EXECUTION_EVENTS_TOPIC, key, event);
            if (future != null) {
                future.whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Async delivery failure for task event taskId={} (key={}): {}",
                                event.getTaskId(), key, ex.getMessage());
                    }
                });
            }
        } catch (Exception ex) {
            log.warn("Failed to publish task event for task {} — continuing execution", event.getTaskId(), ex);
        }
    }
}
