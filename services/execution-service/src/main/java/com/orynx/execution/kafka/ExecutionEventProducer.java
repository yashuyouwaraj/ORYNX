package com.orynx.execution.kafka;

import com.orynx.execution.event.dto.TaskCompletedEvent;
import com.orynx.execution.event.dto.TaskStartedEvent;
import com.orynx.execution.event.dto.WorkflowCompletedEvent;
import com.orynx.execution.event.dto.WorkflowExecutionPausedEvent;
import com.orynx.execution.observability.ExecutionKafkaTracePropagator;
import com.orynx.execution.observability.ExecutionObservabilitySanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutionEventProducer {

    public static final String TOPIC_TASK_STARTED = "task-started";
    public static final String TOPIC_TASK_COMPLETED = "task-completed";
    public static final String TOPIC_WORKFLOW_COMPLETED = "workflow-completed";
    public static final String TOPIC_WORKFLOW_EXECUTION_PAUSED = "workflow-execution-paused";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishTaskStarted(TaskStartedEvent event) {
        if (event == null || event.getWorkflowId() == null) {
            log.warn("Attempted to publish null or unkeyed TaskStartedEvent (workflowId=null). Skipping.");
            return;
        }
        String key = resolveKey(event.getWorkflowId());
        sendWithCallback(TOPIC_TASK_STARTED, key, event, "TaskStartedEvent", event.getTaskName(), event.getEventId());
    }

    public void publishTaskCompleted(TaskCompletedEvent event) {
        if (event == null || event.getWorkflowId() == null) {
            log.warn("Attempted to publish null or unkeyed TaskCompletedEvent (workflowId=null). Skipping.");
            return;
        }
        String key = resolveKey(event.getWorkflowId());
        sendWithCallback(TOPIC_TASK_COMPLETED, key, event, "TaskCompletedEvent", event.getTaskName(), event.getEventId());
    }

    public void publishWorkflowCompleted(WorkflowCompletedEvent event) {
        if (event == null || event.getWorkflowId() == null) {
            log.warn("Attempted to publish null or unkeyed WorkflowCompletedEvent (workflowId=null). Skipping.");
            return;
        }
        String key = resolveKey(event.getWorkflowId());
        sendWithCallback(TOPIC_WORKFLOW_COMPLETED, key, event, "WorkflowCompletedEvent", event.getWorkflowName(), event.getEventId());
    }

    public void publishWorkflowExecutionPaused(WorkflowExecutionPausedEvent event) {
        if (event == null || event.getWorkflowId() == null) {
            log.warn("Attempted to publish null or unkeyed WorkflowExecutionPausedEvent (workflowId=null). Skipping.");
            return;
        }
        String key = resolveKey(event.getWorkflowId());
        sendWithCallback(TOPIC_WORKFLOW_EXECUTION_PAUSED, key, event, "WorkflowExecutionPausedEvent", event.getWorkflowName(), event.getEventId());
    }

    private void sendWithCallback(String topic, String key, Object payload, String eventType, String label, String eventId) {
        String previousEventId = MDC.get(ExecutionKafkaTracePropagator.MDC_EVENT_ID);
        String safeEventId = ExecutionObservabilitySanitizer.sanitizeCorrelationId(eventId);
        if (safeEventId != null) {
            MDC.put(ExecutionKafkaTracePropagator.MDC_EVENT_ID, safeEventId);
        }
        try {
            CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(topic, key, payload);
            if (future != null) {
                future.whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to deliver {} for {} (topic={}, key={}): {}",
                                eventType, label, topic, key, ex.getMessage(), ex);
                    } else if (result != null) {
                        log.debug("Delivered {} for {} to topic={}, partition={}, offset={}",
                                eventType, label, topic,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
            }
            log.info("Published {} for {} (topic={}, key={})", eventType, label, topic, key);
        } finally {
            if (previousEventId == null) {
                MDC.remove(ExecutionKafkaTracePropagator.MDC_EVENT_ID);
            } else {
                MDC.put(ExecutionKafkaTracePropagator.MDC_EVENT_ID, previousEventId);
            }
        }
    }

    private String resolveKey(Long workflowId) {
        return workflowId != null ? String.valueOf(workflowId) : null;
    }
}
