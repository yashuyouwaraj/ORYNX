package com.orynx.orchestrator.workflow.task;

import com.orynx.orchestrator.workflow.event.ExecutionEvent;
import com.orynx.orchestrator.workflow.event.ExecutionEventRepository;
import com.orynx.orchestrator.workflow.task.dto.TaskExecutionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

@Service
@Slf4j
public class TaskEventConsumer {

    private final SimpMessagingTemplate messagingTemplate;
    private final ExecutionEventRepository executionEventRepository;

    public TaskEventConsumer(
            SimpMessagingTemplate messagingTemplate,
            ExecutionEventRepository executionEventRepository
    ) {
        this.messagingTemplate = messagingTemplate;
        this.executionEventRepository = executionEventRepository;
    }

    @KafkaListener(
            topics = TaskEventProducer.TASK_EXECUTION_EVENTS_TOPIC,
            groupId = "orynx-task-group",
            properties = {
                    "spring.json.value.default.type=com.orynx.orchestrator.workflow.task.dto.TaskExecutionEvent",
                    "spring.json.use.type.headers=false"
            }
    )
    @Transactional
    public void consume(TaskExecutionEvent event) {
        if (event == null || event.getWorkflowId() == null) {
            log.warn("Received null or malformed TaskExecutionEvent. Ignoring.");
            return;
        }

        Optional<ExecutionEvent> latestOpt = event.getTaskId() != null
                ? executionEventRepository.findFirstByWorkflowIdAndTaskIdOrderByIdDesc(
                        event.getWorkflowId(), event.getTaskId())
                : executionEventRepository.findFirstByWorkflowIdAndTaskNameOrderByIdDesc(
                        event.getWorkflowId(), event.getTaskName());

        if (latestOpt.isPresent()) {
            ExecutionEvent latest = latestOpt.get();
            if (Objects.equals(latest.getStatus(), event.getStatus())) {
                log.info("Suppressing duplicate TaskExecutionEvent for workflow {} task {} (status={})",
                        event.getWorkflowId(), event.getTaskName(), event.getStatus());
                return;
            }
            if ("COMPLETED".equals(latest.getStatus()) && !"COMPLETED".equals(event.getStatus())) {
                log.info("Suppressing out-of-order TaskExecutionEvent {} after COMPLETED for workflow {} task {}",
                        event.getStatus(), event.getWorkflowId(), event.getTaskName());
                return;
            }
        }

        log.info("Task Event Received: {} (status={})", event.getTaskName(), event.getStatus());

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
