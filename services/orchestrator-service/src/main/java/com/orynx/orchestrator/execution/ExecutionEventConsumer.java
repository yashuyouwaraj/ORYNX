package com.orynx.orchestrator.execution;

import com.orynx.orchestrator.common.metrics.WorkflowMetrics;
import com.orynx.orchestrator.common.observability.WorkflowTracingHelper;
import com.orynx.orchestrator.event.dto.WorkflowExecutionPausedEvent;
import com.orynx.orchestrator.execution.event.TaskCompletedEvent;
import com.orynx.orchestrator.execution.event.TaskStartedEvent;
import com.orynx.orchestrator.execution.event.WorkflowCompletedEvent;
import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowEventPublisher;
import com.orynx.orchestrator.workflow.WorkflowRepository;
import com.orynx.orchestrator.workflow.WorkflowStateValidator;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import com.orynx.orchestrator.workflow.event.WorkflowExecutionEvent;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
public class ExecutionEventConsumer {
    private final WorkflowRepository workflowRepository;
    private final WorkflowTaskRepository workflowTaskRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final WorkflowEventPublisher workflowEventPublisher;

    @Autowired(required = false)
    private WorkflowMetrics workflowMetrics;

    @Autowired(required = false)
    private com.orynx.orchestrator.coordination.admission.WorkflowAdmissionController workflowAdmissionController;

    public void setWorkflowAdmissionController(com.orynx.orchestrator.coordination.admission.WorkflowAdmissionController workflowAdmissionController) {
        this.workflowAdmissionController = workflowAdmissionController;
    }

    public ExecutionEventConsumer(
            WorkflowRepository workflowRepository,
            WorkflowTaskRepository workflowTaskRepository,
            WorkflowExecutionRepository workflowExecutionRepository
    ) {
        this(workflowRepository, workflowTaskRepository, workflowExecutionRepository, null);
    }

    @Autowired
    public ExecutionEventConsumer(
            WorkflowRepository workflowRepository,
            WorkflowTaskRepository workflowTaskRepository,
            WorkflowExecutionRepository workflowExecutionRepository,
            WorkflowEventPublisher workflowEventPublisher
    ) {
        this.workflowRepository = workflowRepository;
        this.workflowTaskRepository = workflowTaskRepository;
        this.workflowExecutionRepository = workflowExecutionRepository;
        this.workflowEventPublisher = workflowEventPublisher;
    }

    @KafkaListener(
            topics = "task-started",
            groupId = "orchestrator-service",
            properties = {
                    "spring.json.value.default.type=com.orynx.orchestrator.execution.event.TaskStartedEvent",
                    "spring.json.use.type.headers=false"
            }
    )
    @Transactional
    public void consumeTaskStarted(TaskStartedEvent event) {
        if (event == null || event.getWorkflowId() == null) {
            log.warn("Received null or malformed TaskStartedEvent. Ignoring.");
            return;
        }
        try (WorkflowTracingHelper.MdcScope ignored = WorkflowTracingHelper.scopedWorkflowLifecycle(
                event.getWorkflowId(), null, event.getGeneration(), "TASK_STARTED", event.getEventId())) {
            log.info("Task Started -> Workflow: {}, Task: {}, gen={}",
                    event.getWorkflowName(), event.getTaskName(), event.getGeneration());

        Optional<WorkflowTask> taskOptional = findTask(event.getWorkflowId(), event.getTaskKey(), event.getExecutionOrder());

        if (taskOptional.isEmpty()) {
            log.warn(
                    "Task not found for workflow {} order {}",
                    event.getWorkflowId(),
                    event.getExecutionOrder()
            );
            return;
        }

        WorkflowTask task = taskOptional.get();

        Optional<Workflow> workflowOptional = workflowRepository.findById(event.getWorkflowId());
        if (workflowOptional.isPresent()) {
            WorkflowStatus wfStatus = workflowOptional.get().getStatus();
            if (wfStatus == WorkflowStatus.CANCELLED
                    || wfStatus == WorkflowStatus.PAUSED
                    || wfStatus == WorkflowStatus.COMPLETED
                    || wfStatus == WorkflowStatus.FAILED) {
                log.info("Workflow {} is in state {}. Suppressing task-started event for task {}.",
                        event.getWorkflowId(), wfStatus, task.getName());
                return;
            }
        }

        Optional<WorkflowExecution> executionOptional =
                workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(event.getWorkflowId());
        if (executionOptional.isPresent()) {
            WorkflowExecution execution = executionOptional.get();
            Long beforeGen = execution.getExecutionGeneration();
            if (isStaleGeneration(execution, event.getGeneration(), event.getWorkflowId(), "task-started")) {
                return;
            }
            if (!Objects.equals(beforeGen, execution.getExecutionGeneration())) {
                workflowExecutionRepository.save(execution);
            }
        }

        if (task.getStatus() == TaskStatus.COMPLETED
                || task.getStatus() == TaskStatus.FAILED
                || task.getStatus() == TaskStatus.SKIPPED
                || task.getStatus() == TaskStatus.CANCELLED) {
            log.info("Task {} for workflow {} is already in terminal state {}. Suppressing out-of-order task-started event.",
                    task.getName(), event.getWorkflowId(), task.getStatus());
            return;
        }

        if (task.getStatus() == TaskStatus.RUNNING && task.getStartedAt() != null) {
            log.info("Task {} for workflow {} is already RUNNING. Suppressing duplicate task-started event.",
                    task.getName(), event.getWorkflowId());
            return;
        }

        task.setStatus(TaskStatus.RUNNING);
        task.setStartedAt(System.currentTimeMillis());

        workflowTaskRepository.save(task);

        log.info("Task {} marked RUNNING", task.getName());
        }
    }

    @KafkaListener(
            topics = "task-completed",
            groupId = "orchestrator-service",
            properties = {
                    "spring.json.value.default.type=com.orynx.orchestrator.execution.event.TaskCompletedEvent",
                    "spring.json.use.type.headers=false"
            }
    )
    @Transactional
    public void consumeTaskCompleted(TaskCompletedEvent event) {
        if (event == null || event.getWorkflowId() == null) {
            log.warn("Received null or malformed TaskCompletedEvent. Ignoring.");
            return;
        }
        try (WorkflowTracingHelper.MdcScope ignored = WorkflowTracingHelper.scopedWorkflowLifecycle(
                event.getWorkflowId(), null, event.getGeneration(), "TASK_COMPLETED", event.getEventId())) {
        log.info(
                "Task Completed -> Workflow: {}, Task: {}, Success: {}, gen={}",
                event.getWorkflowName(),
                event.getTaskName(),
                event.isSuccess(),
                event.getGeneration()
        );

        Optional<WorkflowTask> taskOptional = findTask(event.getWorkflowId(), event.getTaskKey(), event.getExecutionOrder());

        if (taskOptional.isEmpty()) {
            log.warn(
                    "Task not found for workflow {} order {}",
                    event.getWorkflowId(),
                    event.getExecutionOrder()
            );
            return;
        }

        Optional<Workflow> workflowOptional = workflowRepository.findById(event.getWorkflowId());
        if (workflowOptional.isPresent()) {
            WorkflowStatus wfStatus = workflowOptional.get().getStatus();
            if (wfStatus == WorkflowStatus.CANCELLED || wfStatus == WorkflowStatus.COMPLETED) {
                log.info("Workflow {} is in terminal state {}. Suppressing late task-completed event for order {}.",
                        event.getWorkflowId(), wfStatus, event.getExecutionOrder());
                return;
            }
        }

        Optional<WorkflowExecution> executionOptional =
                workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(
                        event.getWorkflowId()
                );

        boolean executionModified = false;
        WorkflowExecution execution = null;
        if (executionOptional.isPresent()) {
            execution = executionOptional.get();
            Long beforeGen = execution.getExecutionGeneration();
            if (isStaleGeneration(execution, event.getGeneration(), event.getWorkflowId(), "task-completed")) {
                return;
            }
            if (!Objects.equals(beforeGen, execution.getExecutionGeneration())) {
                executionModified = true;
            }
        }

        WorkflowTask task = taskOptional.get();
        TaskStatus targetStatus;
        if (event.isSkipped()) {
            targetStatus = TaskStatus.SKIPPED;
        } else if (event.isCancelled()) {
            targetStatus = TaskStatus.CANCELLED;
        } else {
            targetStatus = event.isSuccess() ? TaskStatus.COMPLETED : TaskStatus.FAILED;
        }

        if (task.getStatus() == TaskStatus.COMPLETED && !event.isSuccess()) {
            log.warn("Task {} for workflow {} is already COMPLETED. Suppressing regression to {}.",
                    task.getName(), event.getWorkflowId(), targetStatus);
            return;
        }

        if (task.getStatus() == targetStatus && task.getCompletedAt() != null) {
            log.info("Task {} for workflow {} already marked {}. Suppressing duplicate task-completed update.",
                    task.getName(), event.getWorkflowId(), targetStatus);
        } else {
            task.setStatus(targetStatus);
            task.setCompletedAt(System.currentTimeMillis());
            if (event.getAttempts() > 0) {
                task.setRetryCount(Math.max(0, event.getAttempts() - 1));
            }
            if (event.getOutputJson() != null) {
                task.setOutputJson(event.getOutputJson());
            }
            if (event.getErrorMessage() != null && !event.getErrorMessage().isBlank()) {
                String sanitizedErr = com.orynx.orchestrator.common.observability.ObservabilitySanitizer
                        .redactSensitiveText(event.getErrorMessage());
                task.setErrorMessage(sanitizedErr.length() > 512 ? sanitizedErr.substring(0, 512) : sanitizedErr);
            }
            workflowTaskRepository.save(task);
            if (workflowMetrics != null) {
                workflowMetrics.recordTaskExecuted(targetStatus.name());
            }
            log.info("Task {} marked {}", task.getName(), task.getStatus());
        }

        if (event.isSuccess()) {
            if (execution != null) {
                if (execution.getStatus() != WorkflowStatus.CANCELLED
                        && execution.getStatus() != WorkflowStatus.COMPLETED
                        && execution.getStatus() != WorkflowStatus.FAILED) {
                    Integer candidateWatermark = computeSafeWatermark(event.getWorkflowId(), task, event.getExecutionOrder());
                    if (candidateWatermark != null
                            && (execution.getLastCompletedExecutionOrder() == null
                            || candidateWatermark > execution.getLastCompletedExecutionOrder())) {
                        execution.setLastCompletedExecutionOrder(candidateWatermark);
                        executionModified = true;
                        log.info(
                                "Workflow {} last completed execution order watermark updated to {}",
                                event.getWorkflowId(),
                                candidateWatermark
                        );
                    }
                }
            } else {
                log.warn("Execution record not found for workflow {}", event.getWorkflowId());
            }
        }

        if (executionModified && execution != null
                && execution.getStatus() != WorkflowStatus.CANCELLED) {
            workflowExecutionRepository.save(execution);
        }
        }
    }

    @KafkaListener(
            topics = "workflow-completed",
            groupId = "orchestrator-service",
            properties = {
                    "spring.json.value.default.type=com.orynx.orchestrator.execution.event.WorkflowCompletedEvent",
                    "spring.json.use.type.headers=false"
            }
    )
    @Transactional
    public void consumeWorkflowCompleted(WorkflowCompletedEvent event) {
        if (event == null || event.getWorkflowId() == null) {
            log.warn("Received null or malformed WorkflowCompletedEvent. Ignoring.");
            return;
        }
        try (WorkflowTracingHelper.MdcScope ignored = WorkflowTracingHelper.scopedWorkflowLifecycle(
                event.getWorkflowId(), null, event.getGeneration(), "WORKFLOW_COMPLETED", event.getEventId())) {
        log.info(
                "Workflow Completed -> {} (success={}, gen={})",
                event.getWorkflowName(),
                event.isSuccess(),
                event.getGeneration()
        );

        Optional<Workflow> workflowOptional =
                workflowRepository.findById(event.getWorkflowId());

        if (workflowOptional.isEmpty()) {
            log.warn("Workflow {} not found", event.getWorkflowId());
            return;
        }

        Workflow workflow = workflowOptional.get();

        if (workflow.getStatus() == WorkflowStatus.CANCELLED) {
            log.info(
                    "Workflow {} is in terminal state CANCELLED. Suppressing late workflow-completed event.",
                    workflow.getName()
            );
            return;
        }

        if (workflow.getStatus() == WorkflowStatus.COMPLETED) {
            log.info(
                    "Workflow {} is already in terminal state COMPLETED. Suppressing duplicate completion event.",
                    workflow.getName()
            );
            return;
        }

        if (workflow.getStatus() == WorkflowStatus.FAILED) {
            log.info(
                    "Workflow {} is already in terminal state FAILED. Suppressing duplicate/late workflow-completed event.",
                    workflow.getName()
            );
            return;
        }

        if (workflow.getStatus() == WorkflowStatus.PAUSED) {
            log.warn(
                    "Workflow {} is in state PAUSED. Suppressing late workflow-completed event.",
                    workflow.getName()
            );
            return;
        }

        Optional<WorkflowExecution> executionOptional =
                workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(
                        workflow.getId()
                );

        if (executionOptional.isPresent()
                && isStaleGeneration(executionOptional.get(), event.getGeneration(), workflow.getId(), "workflow-completed")) {
            return;
        }

        WorkflowStatus targetStatus = event.isSuccess()
                ? WorkflowStatus.COMPLETED
                : WorkflowStatus.FAILED;

        if (!WorkflowStateValidator.isValidTransition(workflow.getStatus(), targetStatus)) {
            log.warn("Ignoring invalid workflow-completed transition {} -> {} for workflow {}",
                    workflow.getStatus(), targetStatus, workflow.getId());
            return;
        }

        workflow.setStatus(targetStatus);
        workflow.setCompletedAt(System.currentTimeMillis());

        workflowRepository.save(workflow);

        if (workflowAdmissionController != null) {
            workflowAdmissionController.release(workflow.getId(), workflow.getUserId());
        }

        if (executionOptional.isPresent()) {

            WorkflowExecution execution = executionOptional.get();

            if (execution.getStatus() == WorkflowStatus.CANCELLED) {
                log.info(
                        "Execution history for workflow {} is in terminal state CANCELLED. Preserving status.",
                        workflow.getName()
                );
                return;
            }

            if (execution.getStatus() == WorkflowStatus.COMPLETED
                    || execution.getStatus() == WorkflowStatus.FAILED) {
                log.info(
                        "Execution history for workflow {} is already in terminal state {}. Preserving status.",
                        workflow.getName(),
                        execution.getStatus()
                );
                return;
            }

            execution.setStatus(workflow.getStatus());
            long completedTime = System.currentTimeMillis();

            execution.setCompletedAt(completedTime);

            if (execution.getStartedAt() != null) {
                execution.setDurationMs(
                        completedTime - execution.getStartedAt()
                );
            }

            workflowExecutionRepository.save(execution);

            log.info(
                    "Execution history updated for workflow {}",
                    workflow.getName()
            );
        }

        log.info(
                "Workflow {} marked {}",
                workflow.getName(),
                workflow.getStatus()
        );

        if (workflowMetrics != null) {
            long dur = executionOptional
                    .map(WorkflowExecution::getDurationMs)
                    .filter(Objects::nonNull)
                    .map(d -> Math.max(0L, d))
                    .orElseGet(() -> (workflow.getStartedAt() != null && workflow.getCompletedAt() != null)
                            ? Math.max(0L, workflow.getCompletedAt() - workflow.getStartedAt())
                            : 0L);
            workflowMetrics.recordWorkflowCompleted(workflow.getStatus().name(), dur);
        }

        if (workflowEventPublisher != null) {
            Long gen = executionOptional.map(WorkflowExecution::getExecutionGeneration).orElse(event.getGeneration());
            workflowEventPublisher.publishWorkflowUpdate(WorkflowExecutionEvent.builder()
                    .workflowId(workflow.getId())
                    .workflowName(workflow.getName())
                    .status(workflow.getStatus().name())
                    .userId(workflow.getUserId())
                    .generation(gen)
                    .timestamp(System.currentTimeMillis())
                    .eventId(UUID.randomUUID().toString())
                    .build());
        }
        }
    }

    @KafkaListener(
            topics = "workflow-execution-paused",
            groupId = "orchestrator-service",
            properties = {
                    "spring.json.value.default.type=com.orynx.orchestrator.event.dto.WorkflowExecutionPausedEvent",
                    "spring.json.use.type.headers=false"
            }
    )
    @Transactional
    public void consumeWorkflowExecutionPaused(
            WorkflowExecutionPausedEvent event
    ) {
        if (event == null || event.getWorkflowId() == null) {
            log.warn("Received null or malformed WorkflowExecutionPausedEvent. Ignoring.");
            return;
        }
        try (WorkflowTracingHelper.MdcScope ignored = WorkflowTracingHelper.scopedWorkflowLifecycle(
                event.getWorkflowId(), null, event.getGeneration(), "WORKFLOW_PAUSED", event.getEventId())) {
        log.info(
                "Workflow Execution Paused -> Id: {}, Workflow: {}, Last Completed Task: {}, gen={}",
                event.getWorkflowId(),
                event.getWorkflowName(),
                event.getLastCompletedExecutionOrder(),
                event.getGeneration()
        );

        Optional<WorkflowExecution> executionOptional =
                workflowExecutionRepository
                        .findFirstByWorkflowIdOrderByStartedAtDesc(
                                event.getWorkflowId()
                        );

        if (executionOptional.isEmpty()) {
            log.warn("Execution not found for workflow {}", event.getWorkflowId());
            return;
        }

        WorkflowExecution execution = executionOptional.get();

        if (isStaleGeneration(execution, event.getGeneration(), event.getWorkflowId(), "workflow-execution-paused")) {
            return;
        }

        if (execution.getStatus() == WorkflowStatus.CANCELLED) {
            log.info(
                    "Execution {} is in terminal state CANCELLED. Suppressing late pause event.",
                    execution.getId()
            );
            return;
        }

        if (execution.getStatus() == WorkflowStatus.COMPLETED
                || execution.getStatus() == WorkflowStatus.FAILED) {
            log.info(
                    "Execution {} is in terminal state {}. Suppressing late pause event.",
                    execution.getId(),
                    execution.getStatus()
            );
            return;
        }

        execution.setStatus(WorkflowStatus.PAUSED);

        if (event.getLastCompletedExecutionOrder() != null) {
            if (execution.getLastCompletedExecutionOrder() == null ||
                    event.getLastCompletedExecutionOrder() > execution.getLastCompletedExecutionOrder()) {
                execution.setLastCompletedExecutionOrder(
                        event.getLastCompletedExecutionOrder()
                );
            }
        }

        workflowExecutionRepository.save(execution);

        if (workflowAdmissionController != null) {
            Optional<Workflow> wfOpt = workflowRepository.findById(event.getWorkflowId());
            wfOpt.ifPresent(wf -> workflowAdmissionController.release(wf.getId(), wf.getUserId()));
        }

        log.info(
                "Workflow execution {} marked PAUSED",
                execution.getId()
        );
        }
    }

    /**
     * Computes a safe scalar watermark for presentation / legacy linear compatibility.
     * For DAG workflows (where tasks carry explicit dependsOnJson), only advances the watermark
     * to the highest contiguous completed executionOrder prefix so an out-of-order parallel sibling
     * never implies lower-order tasks are complete.
     */
    private Integer computeSafeWatermark(Long workflowId, WorkflowTask completedTask, Integer eventOrder) {
        if (completedTask != null && completedTask.getDependsOnJson() != null) {
            java.util.List<WorkflowTask> allTasks =
                    workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(workflowId);
            if (allTasks != null && !allTasks.isEmpty()) {
                Integer contiguous = null;
                for (WorkflowTask t : allTasks) {
                    boolean isCompleted = t.getStatus() == TaskStatus.COMPLETED
                            || (completedTask.getId() != null && completedTask.getId().equals(t.getId()))
                            || (completedTask.getTaskKey() != null && completedTask.getTaskKey().equals(t.getTaskKey()));
                    if (!isCompleted) {
                        break;
                    }
                    if (t.getExecutionOrder() != null) {
                        contiguous = t.getExecutionOrder();
                    }
                }
                return contiguous;
            }
        }
        return eventOrder;
    }

    private Optional<WorkflowTask> findTask(Long workflowId, String taskKey, Integer executionOrder) {
        if (taskKey != null && !taskKey.isBlank()) {
            Optional<WorkflowTask> byKey = workflowTaskRepository.findByWorkflowIdAndTaskKey(workflowId, taskKey);
            if (byKey.isPresent()) {
                return byKey;
            }
        }
        if (executionOrder != null) {
            return workflowTaskRepository.findByWorkflowIdAndExecutionOrder(workflowId, executionOrder);
        }
        return Optional.empty();
    }

    private boolean isStaleGeneration(
            WorkflowExecution execution,
            Long eventGeneration,
            Long workflowId,
            String eventType
    ) {
        if (execution == null || eventGeneration == null) {
            return false;
        }
        Long currentGen = execution.getExecutionGeneration();
        if (currentGen == null) {
            execution.setExecutionGeneration(eventGeneration);
            return false;
        }
        if (eventGeneration < currentGen) {
            log.warn("Suppressing stale {} event for workflow {}: eventGen={} < activeExecutionGen={}",
                    eventType, workflowId, eventGeneration, currentGen);
            return true;
        }
        if (eventGeneration > currentGen) {
            execution.setExecutionGeneration(eventGeneration);
        }
        return false;
    }
}
