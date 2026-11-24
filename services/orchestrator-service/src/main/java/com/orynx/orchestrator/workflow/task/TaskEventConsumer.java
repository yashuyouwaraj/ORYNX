package com.orynx.orchestrator.workflow.task;

import com.orynx.orchestrator.common.metrics.WorkflowMetrics;
import com.orynx.orchestrator.execution.WorkflowExecution;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowRepository;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import com.orynx.orchestrator.workflow.event.ExecutionEvent;
import com.orynx.orchestrator.workflow.event.ExecutionEventRepository;
import com.orynx.orchestrator.workflow.task.dto.TaskExecutionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final WorkflowRepository workflowRepository;

    @Autowired(required = false)
    private WorkflowMetrics workflowMetrics;

    public TaskEventConsumer(
            SimpMessagingTemplate messagingTemplate,
            ExecutionEventRepository executionEventRepository
    ) {
        this(messagingTemplate, executionEventRepository, null, null);
    }

    public TaskEventConsumer(
            SimpMessagingTemplate messagingTemplate,
            ExecutionEventRepository executionEventRepository,
            WorkflowExecutionRepository workflowExecutionRepository
    ) {
        this(messagingTemplate, executionEventRepository, workflowExecutionRepository, null);
    }

    @Autowired
    public TaskEventConsumer(
            SimpMessagingTemplate messagingTemplate,
            ExecutionEventRepository executionEventRepository,
            WorkflowExecutionRepository workflowExecutionRepository,
            WorkflowRepository workflowRepository
    ) {
        this.messagingTemplate = messagingTemplate;
        this.executionEventRepository = executionEventRepository;
        this.workflowExecutionRepository = workflowExecutionRepository;
        this.workflowRepository = workflowRepository;
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
        if (event == null || event.getWorkflowId() == null || event.getStatus() == null
                || (event.getTaskId() == null && (event.getTaskName() == null || event.getTaskName().isBlank()))) {
            log.warn("Received null or malformed TaskExecutionEvent. Ignoring.");
            return;
        }

        if (event.getEventId() != null && !event.getEventId().isBlank()
                && executionEventRepository.existsByWorkflowIdAndEventId(event.getWorkflowId(), event.getEventId())) {
            log.info("Suppressing duplicate TaskExecutionEvent by eventId={} for workflow {} task {}",
                    event.getEventId(), event.getWorkflowId(), event.getTaskName());
            return;
        }

        Optional<WorkflowExecution> activeExecution = workflowExecutionRepository != null
                ? workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(event.getWorkflowId())
                : Optional.empty();
        if (activeExecution.isPresent()) {
            WorkflowExecution execution = activeExecution.get();
            if (execution.getStatus() == WorkflowStatus.CANCELLED) {
                log.info("Suppressing late TaskExecutionEvent for CANCELLED workflow {} (task={})",
                        event.getWorkflowId(), event.getTaskName());
                return;
            }
            if (event.getGeneration() != null && execution.getExecutionGeneration() != null
                    && event.getGeneration() < execution.getExecutionGeneration()) {
                log.warn("Suppressing stale TaskExecutionEvent for workflow {} task {}: eventGen={} < activeGen={}",
                        event.getWorkflowId(), event.getTaskName(),
                        event.getGeneration(), execution.getExecutionGeneration());
                return;
            }
        }

        Long effectiveGen = event.getGeneration() != null
                ? event.getGeneration()
                : activeExecution.map(WorkflowExecution::getExecutionGeneration).orElse(null);

        Optional<ExecutionEvent> latestOpt = event.getTaskId() != null
                ? executionEventRepository.findFirstByWorkflowIdAndTaskIdOrderByIdDesc(
                        event.getWorkflowId(), event.getTaskId())
                : executionEventRepository.findFirstByWorkflowIdAndTaskIdIsNullAndTaskNameOrderByIdDesc(
                        event.getWorkflowId(), event.getTaskName());

        if (latestOpt.isPresent()) {
            ExecutionEvent latest = latestOpt.get();
            if (latest.getGeneration() != null && effectiveGen != null && effectiveGen < latest.getGeneration()) {
                log.warn("Suppressing stale TaskExecutionEvent for workflow {} task {}: effectiveGen={} < latestGen={}",
                        event.getWorkflowId(), event.getTaskName(), effectiveGen, latest.getGeneration());
                return;
            }
            if (Objects.equals(latest.getGeneration(), effectiveGen)) {
                if (Objects.equals(latest.getStatus(), event.getStatus())) {
                    log.info("Suppressing duplicate TaskExecutionEvent for workflow {} task {} (status={}, gen={})",
                            event.getWorkflowId(), event.getTaskName(), event.getStatus(), effectiveGen);
                    return;
                }
                if ("COMPLETED".equals(latest.getStatus()) || "FAILED".equals(latest.getStatus())) {
                    log.info("Suppressing out-of-order TaskExecutionEvent {} after {} for workflow {} task {} (gen={})",
                            event.getStatus(), latest.getStatus(),
                            event.getWorkflowId(), event.getTaskName(), effectiveGen);
                    return;
                }
            }
        }

        log.info("Task Event Received: {} (status={}, gen={})", event.getTaskName(), event.getStatus(), effectiveGen);

        ExecutionEvent executionEvent = ExecutionEvent.builder()
                .workflowId(event.getWorkflowId())
                .taskId(event.getTaskId())
                .taskName(event.getTaskName())
                .status(event.getStatus())
                .timestamp(System.currentTimeMillis())
                .generation(effectiveGen)
                .eventId(event.getEventId())
                .build();

        executionEventRepository.save(executionEvent);

        if (workflowMetrics != null && ("COMPLETED".equals(event.getStatus()) || "FAILED".equals(event.getStatus()))) {
            workflowMetrics.recordTaskExecuted(event.getStatus());
        }

        event.setGeneration(effectiveGen);
        event.setTimestamp(executionEvent.getTimestamp());

        messagingTemplate.convertAndSend(
                "/topic/workflows/" + event.getWorkflowId() + "/tasks",
                event
        );

        if (workflowRepository != null) {
            workflowRepository.findById(event.getWorkflowId())
                    .map(Workflow::getUserId)
                    .ifPresent(userId -> messagingTemplate.convertAndSendToUser(
                            String.valueOf(userId),
                            "/queue/tasks",
                            event
                    ));
        }
    }
}
