package com.orynx.orchestrator.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.orchestrator.common.exception.WorkflowNotFoundException;
import com.orynx.orchestrator.common.metrics.WorkflowMetrics;
import com.orynx.orchestrator.common.observability.WorkflowTracingHelper;
import com.orynx.orchestrator.common.security.UserContext;
import com.orynx.orchestrator.common.util.PaginationUtils;
import com.orynx.orchestrator.event.dto.WorkflowCancelledEvent;
import com.orynx.orchestrator.event.dto.WorkflowPausedEvent;
import com.orynx.orchestrator.event.dto.WorkflowResumedEvent;
import com.orynx.orchestrator.execution.WorkflowExecution;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.kafka.*;
import com.orynx.orchestrator.workflow.dag.DagValidator;
import com.orynx.orchestrator.workflow.dto.CreateWorkflowRequest;
import com.orynx.orchestrator.workflow.dto.DashboardSummaryResponse;
import com.orynx.orchestrator.workflow.event.WorkflowCreatedEvent;
import com.orynx.orchestrator.workflow.event.WorkflowExecutionEvent;
import com.orynx.orchestrator.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.orchestrator.workflow.event.dto.TaskExecutionRequest;
import com.orynx.orchestrator.workflow.execution.dto.WorkflowAnalyticsResponse;
import com.orynx.orchestrator.workflow.execution.dto.WorkflowExecutionResponse;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowExecutionEngine;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskDependency;
import com.orynx.orchestrator.workflow.task.WorkflowTaskDependencyRepository;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowService {
    private final WorkflowRepository workflowRepository;
    private final KafkaProducer kafkaProducer;
    private final WorkflowEventPublisher workflowEventPublisher;
    private final WorkflowTaskRepository workflowTaskRepository;
    private final WorkflowExecutionEngine workflowExecutionEngine;
    private final ExecutionRequestProducer executionRequestProducer;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final WorkflowCancellationProducer workflowCancellationProducer;
    private final WorkflowPauseProducer workflowPauseProducer;
    private final WorkflowResumeProducer workflowResumeProducer;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorkflowMetrics workflowMetrics;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.orynx.orchestrator.coordination.admission.WorkflowAdmissionController workflowAdmissionController;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorkflowTaskDependencyRepository workflowTaskDependencyRepository;

    private final DagValidator dagValidator = new DagValidator();
    private final ObjectMapper dagObjectMapper = new ObjectMapper();

    public void setWorkflowAdmissionController(com.orynx.orchestrator.coordination.admission.WorkflowAdmissionController workflowAdmissionController) {
        this.workflowAdmissionController = workflowAdmissionController;
    }

    public Workflow createWorkflow(CreateWorkflowRequest request) {
        return createWorkflow(request, UserContext.getUserId());
    }

    public Workflow createWorkflow(CreateWorkflowRequest request, Long userId) {
        log.info("Creating workflow: {} for userId: {}", request.getName(), userId);

        DagValidator.ValidatedDag validatedDag = null;
        if (request.getTasks() != null && !request.getTasks().isEmpty()) {
            validatedDag = dagValidator.validateAndNormalize(request.getTasks());
        }

        Workflow dependsOn = null;
        if (request.getDependsOnWorkflowId() != null) {
            dependsOn = workflowRepository.findById(request.getDependsOnWorkflowId())
                    .orElseThrow(() -> new WorkflowNotFoundException(
                            "Dependent workflow not found with id: " + request.getDependsOnWorkflowId()
                    ));
        }

        Workflow workflow = Workflow.builder()
                .name(request.getName())
                .scheduledAt(request.getScheduledAt())
                .scheduled(request.getScheduledAt() != null)
                .goal(request.getGoal())
                .status(WorkflowStatus.CREATED)
                .dependsOnWorkflow(dependsOn)
                .userId(userId)
                .build();

        Workflow savedWorkflow = workflowRepository.save(workflow);

        if (validatedDag != null) {
            persistValidatedDagTasks(savedWorkflow, validatedDag);
        } else {
            createWorkflowTasks(savedWorkflow);
        }

        try (WorkflowTracingHelper.MdcScope ignored =
                     WorkflowTracingHelper.scopedWorkflowLifecycle(savedWorkflow.getId(), null, null, "CREATED")) {
            WorkflowCreatedEvent event = WorkflowCreatedEvent.builder()
                    .workflowId(savedWorkflow.getId())
                    .workflowName(savedWorkflow.getName())
                    .goal(savedWorkflow.getGoal())
                    .build();

            kafkaProducer.publishWorkflowCreatedEvent(event);

            WorkflowExecutionEvent executionEvent = WorkflowExecutionEvent.builder()
                    .workflowId(savedWorkflow.getId())
                    .workflowName(savedWorkflow.getName())
                    .status(savedWorkflow.getStatus().name())
                    .userId(savedWorkflow.getUserId())
                    .build();

            workflowEventPublisher.publishWorkflowUpdate(executionEvent);
        }

        if (workflowMetrics != null) {
            workflowMetrics.recordWorkflowCreated();
        }

        return savedWorkflow;
    }

    @Transactional
    public Workflow startWorkflow(Long workflowId) {
        return startWorkflow(workflowId, UserContext.getUserId());
    }

    @Transactional
    public Workflow startWorkflow(Long workflowId, Long userId) {
        Workflow workflow = getWorkflowForUpdate(workflowId, userId);

        if (workflow.getStatus() != WorkflowStatus.CREATED) {
            if (workflow.getStatus() == WorkflowStatus.RUNNING) {
                throw new IllegalStateException("Cannot start workflow: workflow is already RUNNING");
            } else if (workflow.getStatus() == WorkflowStatus.PAUSED) {
                throw new IllegalStateException("Cannot start workflow: workflow is PAUSED. Use resume instead.");
            } else if (workflow.getStatus() == WorkflowStatus.FAILED) {
                throw new IllegalStateException("Cannot start workflow: workflow is in terminal state FAILED. Use retry instead.");
            } else {
                throw new IllegalStateException("Cannot start workflow: workflow is in terminal state " + workflow.getStatus());
            }
        }

        WorkflowStateValidator.validateTransition(workflow.getStatus(), WorkflowStatus.RUNNING, "start");

        if (workflow.getDependsOnWorkflow() != null) {
            Workflow dependency = workflow.getDependsOnWorkflow();
            if (dependency.getStatus() != WorkflowStatus.COMPLETED) {
                throw new IllegalStateException(String.format(
                        "Cannot start workflow: dependent workflow %d is not COMPLETED (current status: %s)",
                        dependency.getId(), dependency.getStatus()
                ));
            }
        }

        if (workflowAdmissionController != null) {
            workflowAdmissionController.tryAdmit(workflow.getId(), workflow.getUserId());
        }

        try {
            ensureWorkflowTasksExist(workflow);

            workflow.setStatus(WorkflowStatus.RUNNING);
            workflow.setStartedAt(System.currentTimeMillis());
            if (Boolean.TRUE.equals(workflow.getScheduled())) {
                workflow.setScheduled(false);
            }

            Workflow updatedWorkflow = workflowRepository.save(workflow);

            WorkflowExecution execution = WorkflowExecution.builder()
                    .workflow(updatedWorkflow)
                    .status(WorkflowStatus.RUNNING)
                    .startedAt(System.currentTimeMillis())
                    .executionGeneration(1L)
                    .build();

            WorkflowExecution savedExecution = workflowExecutionRepository.save(execution);
            if (savedExecution == null) {
                savedExecution = execution;
            }
            String executionEventId = UUID.randomUUID().toString();

            try (WorkflowTracingHelper.MdcScope ignored = WorkflowTracingHelper.scopedWorkflowLifecycle(
                    updatedWorkflow.getId(), savedExecution.getId(), 1L, "CREATED->RUNNING", executionEventId)) {
                log.info(
                        "Created execution history record for workflow {}",
                        updatedWorkflow.getName()
                );

                List<WorkflowTask> workflowTasks =
                        workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(
                                workflow.getId()
                        );
                List<TaskExecutionRequest> taskRequests = workflowTasks.stream()
                        .map(this::toTaskExecutionRequest)
                        .toList();

                executionRequestProducer.publishExecutionRequest(
                        WorkflowExecutionRequestEvent.builder()
                                .workflowId(workflow.getId())
                                .workflowName(workflow.getName())
                                .tasks(taskRequests)
                                .eventId(executionEventId)
                                .executionId(savedExecution.getId())
                                .resume(false)
                                .executionMode("INITIAL")
                                .generation(1L)
                                .build()
                );

                WorkflowExecutionEvent event = WorkflowExecutionEvent.builder()
                        .workflowId(updatedWorkflow.getId())
                        .workflowName(updatedWorkflow.getName())
                        .status(updatedWorkflow.getStatus().name())
                        .userId(updatedWorkflow.getUserId())
                        .generation(1L)
                        .build();

                workflowEventPublisher.publishWorkflowUpdate(event);
            }

            if (workflowMetrics != null) {
                workflowMetrics.recordWorkflowStarted();
            }

            return updatedWorkflow;
        } catch (Exception e) {
            if (workflowAdmissionController != null) {
                try {
                    workflowAdmissionController.release(workflow.getId(), workflow.getUserId());
                } catch (Exception releaseEx) {
                    log.error("Failed to release admission slot after startWorkflow failure for workflow {}: {}",
                            workflow.getId(), releaseEx.getMessage());
                }
            }
            throw e;
        }
    }

    public Page<Workflow> getAllWorkflows(Pageable pageable) {
        return getAllWorkflows(pageable, UserContext.getUserId());
    }

    public Page<Workflow> getAllWorkflows(Pageable pageable, Long userId) {
        Pageable normalized = PaginationUtils.normalize(
                pageable,
                Sort.by(Sort.Direction.DESC, "id"),
                PaginationUtils.WORKFLOW_SORT_FIELDS
        );
        if (userId != null) {
            return workflowRepository.findByUserId(userId, normalized);
        } else {
            return workflowRepository.findAll(normalized);
        }
    }

    public Workflow getWorkflow(Long workflowId) {
        return getWorkflow(workflowId, UserContext.getUserId());
    }

    public Workflow getWorkflow(Long workflowId, Long userId) {
        if (userId != null) {
            return workflowRepository.findByIdAndUserId(workflowId, userId)
                    .orElseThrow(() -> new WorkflowNotFoundException(
                            "Workflow not found: " + workflowId
                    ));
        } else {
            return workflowRepository.findById(workflowId)
                    .orElseThrow(() -> new WorkflowNotFoundException(
                            "Workflow not found: " + workflowId
                    ));
        }
    }

    public Workflow getWorkflowForUpdate(Long workflowId, Long userId) {
        Optional<Workflow> locked = (userId != null)
                ? workflowRepository.findByIdAndUserIdForUpdate(workflowId, userId)
                : workflowRepository.findByIdForUpdate(workflowId);

        if (locked.isPresent()) {
            return locked.get();
        }
        // Fallback to standard lookup (supports unit test environments where only findById is stubbed)
        return getWorkflow(workflowId, userId);
    }

    public List<Workflow> getWorkflowsByStatus(WorkflowStatus status) {
        return getWorkflowsByStatus(status, UserContext.getUserId());
    }

    public List<Workflow> getWorkflowsByStatus(WorkflowStatus status, Long userId) {
        if (userId != null) {
            return workflowRepository.findByUserIdAndStatusOrderByIdDesc(userId, status);
        } else {
            return workflowRepository.findByStatusOrderByIdDesc(status);
        }
    }

    public Page<Workflow> getWorkflowsByStatus(WorkflowStatus status, Pageable pageable) {
        return getWorkflowsByStatus(status, pageable, UserContext.getUserId());
    }

    public Page<Workflow> getWorkflowsByStatus(WorkflowStatus status, Pageable pageable, Long userId) {
        Pageable normalized = PaginationUtils.normalize(
                pageable,
                Sort.by(Sort.Direction.DESC, "id"),
                PaginationUtils.WORKFLOW_SORT_FIELDS
        );
        if (userId != null) {
            return workflowRepository.findByUserIdAndStatus(userId, status, normalized);
        } else {
            return workflowRepository.findByStatus(status, normalized);
        }
    }

    public List<WorkflowTask> getWorkflowTasks(Long workflowId) {
        return getWorkflowTasks(workflowId, UserContext.getUserId());
    }

    public List<WorkflowTask> getWorkflowTasks(Long workflowId, Long userId) {
        // Enforce ownership: caller must have access to workflow
        getWorkflow(workflowId, userId);
        return workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(workflowId);
    }

    public Page<WorkflowTask> getWorkflowTasks(Long workflowId, Pageable pageable) {
        return getWorkflowTasks(workflowId, pageable, UserContext.getUserId());
    }

    public Page<WorkflowTask> getWorkflowTasks(Long workflowId, Pageable pageable, Long userId) {
        getWorkflow(workflowId, userId);
        Pageable normalized = PaginationUtils.normalize(
                pageable,
                Sort.by(Sort.Order.asc("executionOrder"), Sort.Order.asc("id")),
                PaginationUtils.TASK_SORT_FIELDS
        );
        return workflowTaskRepository.findByWorkflowId(workflowId, normalized);
    }

    private void createWorkflowTasks(Workflow workflow) {
        List<WorkflowTask> tasks = List.of(
                WorkflowTask.builder()
                        .name("Validate Payload")
                        .executionOrder(1)
                        .status(TaskStatus.PENDING)
                        .maxRetries(2)
                        .workflow(workflow)
                        .build(),

                WorkflowTask.builder()
                        .name("Generate Report")
                        .executionOrder(2)
                        .status(TaskStatus.PENDING)
                        .maxRetries(3)
                        .workflow(workflow)
                        .build(),

                WorkflowTask.builder()
                        .name("Collect Metrics")
                        .executionOrder(3)
                        .status(TaskStatus.PENDING)
                        .maxRetries(3)
                        .workflow(workflow)
                        .build(),

                WorkflowTask.builder()
                        .name("Analyze Logs")
                        .executionOrder(4)
                        .status(TaskStatus.PENDING)
                        .maxRetries(3)
                        .workflow(workflow)
                        .build(),

                WorkflowTask.builder()
                        .name("Store Results")
                        .executionOrder(5)
                        .status(TaskStatus.PENDING)
                        .maxRetries(3)
                        .workflow(workflow)
                        .build(),

                WorkflowTask.builder()
                        .name("Notify Stakeholders")
                        .executionOrder(6)
                        .status(TaskStatus.PENDING)
                        .maxRetries(3)
                        .workflow(workflow)
                        .build()
        );

        workflowTaskRepository.saveAll(tasks);
    }

    private void persistValidatedDagTasks(Workflow workflow, DagValidator.ValidatedDag validatedDag) {
        List<WorkflowTask> entities = validatedDag.topologicalTasks().stream()
                .map(spec -> WorkflowTask.builder()
                        .taskKey(spec.taskKey())
                        .name(spec.name())
                        .taskType(spec.taskType())
                        .executionOrder(spec.executionOrder())
                        .dependsOnJson(toJsonSafe(spec.dependsOn()))
                        .configJson(spec.config().isEmpty() ? null : toJsonSafe(spec.config()))
                        .timeoutMs(spec.timeoutMs())
                        .maxRetries(spec.maxRetries())
                        .status(TaskStatus.PENDING)
                        .workflow(workflow)
                        .build())
                .toList();
        workflowTaskRepository.saveAll(entities);

        if (workflowTaskDependencyRepository != null && workflow.getId() != null) {
            List<WorkflowTaskDependency> edges = validatedDag.topologicalTasks().stream()
                    .flatMap(spec -> spec.dependsOn().stream()
                            .map(dep -> WorkflowTaskDependency.builder()
                                    .workflowId(workflow.getId())
                                    .taskKey(spec.taskKey())
                                    .dependsOnTaskKey(dep)
                                    .build()))
                    .toList();
            if (!edges.isEmpty()) {
                workflowTaskDependencyRepository.saveAll(edges);
            }
        }
    }

    private TaskExecutionRequest toTaskExecutionRequest(WorkflowTask task) {
        String key = (task.getTaskKey() != null && !task.getTaskKey().isBlank())
                ? task.getTaskKey()
                : (task.getExecutionOrder() != null ? "task-" + task.getExecutionOrder() : null);
        return TaskExecutionRequest.builder()
                .taskKey(key)
                .name(task.getName())
                .taskType(task.getTaskType() != null ? task.getTaskType() : "NOOP")
                .dependsOn(task.getDependsOnJson() != null ? task.getDependsOn() : null)
                .config(fromConfigJson(task.getConfigJson()))
                .timeoutMs(task.getTimeoutMs() != null ? task.getTimeoutMs() : DagValidator.DEFAULT_TIMEOUT_MS)
                .executionOrder(task.getExecutionOrder())
                .maxRetries(task.getMaxRetries())
                .build();
    }

    private String toJsonSafe(Object value) {
        try {
            return dagObjectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> fromConfigJson(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Map.of();
        }
        try {
            return dagObjectMapper.readValue(configJson, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void ensureWorkflowTasksExist(Workflow workflow) {
        List<WorkflowTask> existingTasks =
                workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(
                        workflow.getId()
                );

        if (existingTasks.isEmpty()) {
            createWorkflowTasks(workflow);
        }
    }

    public DashboardSummaryResponse getDashboardSummary() {
        return getDashboardSummary(UserContext.getUserId());
    }

    public DashboardSummaryResponse getDashboardSummary(Long userId) {
        List<Object[]> grouped = (userId == null)
                ? workflowRepository.countGroupedByStatus()
                : workflowRepository.countGroupedByStatusByUserId(userId);

        Map<WorkflowStatus, Long> counts = new EnumMap<>(WorkflowStatus.class);
        long total = 0L;
        if (grouped != null) {
            for (Object[] row : grouped) {
                if (row != null && row.length >= 2 && row[0] instanceof WorkflowStatus status && row[1] instanceof Number num) {
                    long count = num.longValue();
                    counts.put(status, count);
                    total += count;
                }
            }
        }

        return DashboardSummaryResponse.builder()
                .totalWorkflows(total)
                .runningWorkflows(counts.getOrDefault(WorkflowStatus.RUNNING, 0L))
                .pausedWorkflows(counts.getOrDefault(WorkflowStatus.PAUSED, 0L))
                .completedWorkflows(counts.getOrDefault(WorkflowStatus.COMPLETED, 0L))
                .failedWorkflows(counts.getOrDefault(WorkflowStatus.FAILED, 0L))
                .createdWorkflows(counts.getOrDefault(WorkflowStatus.CREATED, 0L))
                .cancelledWorkflows(counts.getOrDefault(WorkflowStatus.CANCELLED, 0L))
                .build();
    }

    public List<WorkflowExecutionResponse> getWorkflowExecutionHistory(Long workflowId) {
        return getWorkflowExecutionHistory(workflowId, UserContext.getUserId());
    }

    public List<WorkflowExecutionResponse> getWorkflowExecutionHistory(Long workflowId, Long userId) {
        getWorkflow(workflowId, userId);

        return workflowExecutionRepository
                .findByWorkflowIdOrderByStartedAtDesc(workflowId)
                .stream()
                .map(execution ->
                        WorkflowExecutionResponse.builder()
                                .id(execution.getId())
                                .status(execution.getStatus())
                                .startedAt(execution.getStartedAt())
                                .completedAt(execution.getCompletedAt())
                                .build()
                )
                .toList();
    }

    public Page<WorkflowExecutionResponse> getWorkflowExecutionHistory(Long workflowId, Pageable pageable) {
        return getWorkflowExecutionHistory(workflowId, pageable, UserContext.getUserId());
    }

    public Page<WorkflowExecutionResponse> getWorkflowExecutionHistory(Long workflowId, Pageable pageable, Long userId) {
        getWorkflow(workflowId, userId);
        Pageable normalized = PaginationUtils.normalize(
                pageable,
                Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id")),
                PaginationUtils.EXECUTION_SORT_FIELDS
        );

        return workflowExecutionRepository
                .findByWorkflowId(workflowId, normalized)
                .map(execution ->
                        WorkflowExecutionResponse.builder()
                                .id(execution.getId())
                                .status(execution.getStatus())
                                .startedAt(execution.getStartedAt())
                                .completedAt(execution.getCompletedAt())
                                .build()
                );
    }

    public WorkflowAnalyticsResponse getWorkflowAnalytics(Long workflowId) {
        return getWorkflowAnalytics(workflowId, UserContext.getUserId());
    }

    public WorkflowAnalyticsResponse getWorkflowAnalytics(Long workflowId, Long userId) {
        Workflow workflow = getWorkflow(workflowId, userId);

        List<WorkflowExecution> executions =
                workflowExecutionRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId);

        if (executions.isEmpty()) {
            return WorkflowAnalyticsResponse.builder()
                    .workflowId(workflow.getId())
                    .workflowName(workflow.getName())
                    .build();
        }

        long total = executions.size();

        long successful = executions.stream()
                .filter(e -> e.getStatus() == WorkflowStatus.COMPLETED)
                .count();

        long failed = executions.stream()
                .filter(e -> e.getStatus() == WorkflowStatus.FAILED)
                .count();

        double successRate = (successful * 100.0) / total;

        List<Long> durations = executions.stream()
                .map(WorkflowExecution::getDurationMs)
                .filter(Objects::nonNull)
                .toList();

        long averageDuration = (long) durations.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0);

        long fastest = durations.stream()
                .mapToLong(Long::longValue)
                .min()
                .orElse(0);

        long slowest = durations.stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0);

        return WorkflowAnalyticsResponse.builder()
                .workflowId(workflow.getId())
                .workflowName(workflow.getName())
                .totalExecutions(total)
                .successfulExecutions(successful)
                .failedExecutions(failed)
                .successRate(successRate)
                .averageDurationMs(averageDuration)
                .fastestExecutionMs(fastest)
                .slowestExecutionMs(slowest)
                .build();
    }

    @Transactional
    public Workflow cancelWorkflow(Long workflowId) {
        return cancelWorkflow(workflowId, UserContext.getUserId());
    }

    @Transactional
    public Workflow cancelWorkflow(Long workflowId, Long userId) {
        Workflow workflow = getWorkflowForUpdate(workflowId, userId);

        // Idempotent cancellation: If already cancelled, return safely without side-effects
        if (workflow.getStatus() == WorkflowStatus.CANCELLED) {
            log.info("Workflow {} is already CANCELLED. Returning idempotently.", workflow.getId());
            return workflow;
        }

        if (workflow.getStatus() == WorkflowStatus.COMPLETED) {
            throw new IllegalStateException("Cannot cancel workflow: workflow is in terminal state COMPLETED");
        }
        if (workflow.getStatus() == WorkflowStatus.FAILED) {
            throw new IllegalStateException("Cannot cancel workflow: workflow is in terminal state FAILED");
        }

        WorkflowStateValidator.validateTransition(workflow.getStatus(), WorkflowStatus.CANCELLED, "cancel");

        workflow.setStatus(WorkflowStatus.CANCELLED);
        workflow.setCompletedAt(System.currentTimeMillis());
        if (Boolean.TRUE.equals(workflow.getScheduled())) {
            workflow.setScheduled(false);
        }

        Workflow updatedWorkflow = workflowRepository.save(workflow);

        if (workflowAdmissionController != null) {
            workflowAdmissionController.release(workflow.getId(), workflow.getUserId());
        }

        Optional<WorkflowExecution> executionOptional = workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(workflowId);
        Long activeExecutionId = executionOptional.map(WorkflowExecution::getId).orElse(null);
        Long activeGeneration = executionOptional.map(WorkflowExecution::getExecutionGeneration).orElse(null);
        String cancelEventId = UUID.randomUUID().toString();

        try (WorkflowTracingHelper.MdcScope ignored = WorkflowTracingHelper.scopedWorkflowLifecycle(
                updatedWorkflow.getId(), activeExecutionId, activeGeneration, "CANCELLED", cancelEventId)) {
            workflowCancellationProducer.publishWorkflowCancelled(
                    WorkflowCancelledEvent.builder()
                            .workflowId(workflow.getId())
                            .workflowName(workflow.getName())
                            .eventId(cancelEventId)
                            .build()
            );

            if (executionOptional.isPresent()) {
                WorkflowExecution execution = executionOptional.get();

                execution.setStatus(WorkflowStatus.CANCELLED);

                long completedTime = System.currentTimeMillis();

                execution.setCompletedAt(completedTime);

                if (execution.getStartedAt() != null) {
                    execution.setDurationMs(completedTime - execution.getStartedAt());
                }

                workflowExecutionRepository.save(execution);
            }

            WorkflowExecutionEvent event = WorkflowExecutionEvent.builder()
                    .workflowId(updatedWorkflow.getId())
                    .workflowName(updatedWorkflow.getName())
                    .status(updatedWorkflow.getStatus().name())
                    .userId(updatedWorkflow.getUserId())
                    .build();

            workflowEventPublisher.publishWorkflowUpdate(event);

            if (workflowMetrics != null) {
                workflowMetrics.recordWorkflowCancelled();
            }

            log.info("Workflow {} cancelled.", updatedWorkflow.getName());
        }

        return updatedWorkflow;
    }

    @Transactional
    public Workflow pauseWorkflow(Long workflowId) {
        return pauseWorkflow(workflowId, UserContext.getUserId());
    }

    @Transactional
    public Workflow pauseWorkflow(Long workflowId, Long userId) {
        Workflow workflow = getWorkflowForUpdate(workflowId, userId);

        if (workflow.getStatus() != WorkflowStatus.RUNNING) {
            if (workflow.getStatus() == WorkflowStatus.PAUSED) {
                throw new IllegalStateException("Cannot pause workflow: workflow is already PAUSED");
            } else if (workflow.getStatus() == WorkflowStatus.CREATED) {
                throw new IllegalStateException("Cannot pause workflow: workflow is in CREATED state");
            } else {
                throw new IllegalStateException("Cannot pause workflow: workflow is in terminal state " + workflow.getStatus());
            }
        }

        WorkflowStateValidator.validateTransition(workflow.getStatus(), WorkflowStatus.PAUSED, "pause");

        workflow.setStatus(WorkflowStatus.PAUSED);

        Workflow updatedWorkflow = workflowRepository.save(workflow);

        if (workflowAdmissionController != null) {
            workflowAdmissionController.release(workflow.getId(), workflow.getUserId());
        }

        Optional<WorkflowExecution> executionOptional =
                workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(workflowId);
        Long activeExecutionId = executionOptional.map(WorkflowExecution::getId).orElse(null);
        Long activeGeneration = executionOptional.map(WorkflowExecution::getExecutionGeneration).orElse(null);
        String pauseEventId = UUID.randomUUID().toString();
        try (WorkflowTracingHelper.MdcScope ignored = WorkflowTracingHelper.scopedWorkflowLifecycle(
                updatedWorkflow.getId(), activeExecutionId, activeGeneration, "RUNNING->PAUSED", pauseEventId)) {
            workflowPauseProducer.publishWorkflowPaused(
                    WorkflowPausedEvent.builder()
                            .workflowId(updatedWorkflow.getId())
                            .workflowName(updatedWorkflow.getName())
                            .eventId(pauseEventId)
                            .build()
            );

            WorkflowExecutionEvent event = WorkflowExecutionEvent.builder()
                    .workflowId(updatedWorkflow.getId())
                    .workflowName(updatedWorkflow.getName())
                    .status(updatedWorkflow.getStatus().name())
                    .userId(updatedWorkflow.getUserId())
                    .build();

            workflowEventPublisher.publishWorkflowUpdate(event);

            log.info(
                    "Workflow {} paused.",
                    updatedWorkflow.getName()
            );
        }

        return updatedWorkflow;
    }

    @Transactional
    public Workflow resumeWorkflow(Long workflowId) {
        return resumeWorkflow(workflowId, UserContext.getUserId());
    }

    @Transactional
    public Workflow resumeWorkflow(Long workflowId, Long userId) {
        Workflow workflow = getWorkflowForUpdate(workflowId, userId);

        if (workflow.getStatus() != WorkflowStatus.PAUSED) {
            if (workflow.getStatus() == WorkflowStatus.RUNNING) {
                throw new IllegalStateException("Cannot resume workflow: workflow is already RUNNING");
            } else if (workflow.getStatus() == WorkflowStatus.CREATED) {
                throw new IllegalStateException("Cannot resume workflow: workflow is in CREATED state. Use start instead.");
            } else if (workflow.getStatus() == WorkflowStatus.FAILED) {
                throw new IllegalStateException("Cannot resume workflow: workflow is in terminal state FAILED. Use retry instead.");
            } else {
                throw new IllegalStateException("Cannot resume workflow: workflow is in terminal state " + workflow.getStatus());
            }
        }

        WorkflowStateValidator.validateTransition(workflow.getStatus(), WorkflowStatus.RUNNING, "resume");

        WorkflowExecution execution =
                workflowExecutionRepository
                        .findFirstByWorkflowIdOrderByStartedAtDesc(workflowId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Execution record not found for workflow"
                                )
                        );

        List<WorkflowTask> workflowTasks =
                workflowTaskRepository
                        .findByWorkflowIdOrderByExecutionOrder(workflowId);

        boolean isDagWorkflow = workflowTasks.stream()
                .anyMatch(t -> t.getDependsOnJson() != null);

        List<TaskExecutionRequest> tasksToDispatch;
        List<String> completedTaskKeys = null;
        int remainingIncompleteCount;

        if (isDagWorkflow) {
            List<WorkflowTask> incompleteTasks = workflowTasks.stream()
                    .filter(task -> task.getStatus() != TaskStatus.COMPLETED
                            && task.getStatus() != TaskStatus.SKIPPED)
                    .toList();

            if (incompleteTasks.isEmpty()) {
                throw new IllegalStateException(
                        "No remaining tasks to resume for workflow " + workflowId
                );
            }

            completedTaskKeys = workflowTasks.stream()
                    .filter(task -> task.getStatus() == TaskStatus.COMPLETED)
                    .map(task -> (task.getTaskKey() != null && !task.getTaskKey().isBlank())
                            ? task.getTaskKey()
                            : (task.getExecutionOrder() != null ? "task-" + task.getExecutionOrder() : null))
                    .filter(Objects::nonNull)
                    .toList();

            // Publish the FULL DAG graph so execution-service reconstructs all dependency edges
            // and hydrates upstream outputs from durable COMPLETED checkpoints.
            tasksToDispatch = workflowTasks.stream()
                    .map(this::toTaskExecutionRequest)
                    .toList();
            remainingIncompleteCount = incompleteTasks.size();
        } else {
            Integer lastCompletedOrder = execution.getLastCompletedExecutionOrder();
            final int resumeFromOrder = lastCompletedOrder == null ? 0 : lastCompletedOrder;

            tasksToDispatch = workflowTasks.stream()
                    .filter(task -> task.getStatus() != TaskStatus.COMPLETED
                            && (task.getExecutionOrder() == null || task.getExecutionOrder() > resumeFromOrder))
                    .map(this::toTaskExecutionRequest)
                    .toList();

            if (tasksToDispatch.isEmpty()) {
                throw new IllegalStateException(
                        "No remaining tasks to resume for workflow " + workflowId
                );
            }
            remainingIncompleteCount = tasksToDispatch.size();
        }

        long nextExecutionGeneration = (execution.getExecutionGeneration() != null
                ? execution.getExecutionGeneration()
                : 1L) + 1L;

        if (workflowAdmissionController != null) {
            workflowAdmissionController.tryAdmit(workflow.getId(), workflow.getUserId());
        }

        try {
            workflow.setStatus(WorkflowStatus.RUNNING);

            Workflow updatedWorkflow =
                    workflowRepository.save(workflow);

            execution.setStatus(WorkflowStatus.RUNNING);
            execution.setExecutionGeneration(nextExecutionGeneration);

            workflowExecutionRepository.save(execution);
            String resumeEventId = UUID.randomUUID().toString();
            String execRequestEventId = UUID.randomUUID().toString();

            try (WorkflowTracingHelper.MdcScope ignored = WorkflowTracingHelper.scopedWorkflowLifecycle(
                    updatedWorkflow.getId(), execution.getId(), nextExecutionGeneration, "PAUSED->RUNNING", resumeEventId)) {
                workflowResumeProducer.publishWorkflowResumed(
                        WorkflowResumedEvent.builder()
                                .workflowId(updatedWorkflow.getId())
                                .workflowName(updatedWorkflow.getName())
                                .eventId(resumeEventId)
                                .build()
                );
            }

            try (WorkflowTracingHelper.MdcScope ignored = WorkflowTracingHelper.scopedWorkflowLifecycle(
                    updatedWorkflow.getId(), execution.getId(), nextExecutionGeneration, "PAUSED->RUNNING", execRequestEventId)) {
                executionRequestProducer.publishExecutionRequest(
                        WorkflowExecutionRequestEvent.builder()
                                .workflowId(updatedWorkflow.getId())
                                .workflowName(updatedWorkflow.getName())
                                .tasks(tasksToDispatch)
                                .eventId(execRequestEventId)
                                .executionId(execution.getId())
                                .resume(true)
                                .executionMode("RESUME")
                                .completedTaskKeys(completedTaskKeys)
                                .generation(nextExecutionGeneration)
                                .build()
                );

                WorkflowExecutionEvent event = WorkflowExecutionEvent.builder()
                        .workflowId(updatedWorkflow.getId())
                        .workflowName(updatedWorkflow.getName())
                        .status(updatedWorkflow.getStatus().name())
                        .userId(updatedWorkflow.getUserId())
                        .generation(nextExecutionGeneration)
                        .build();

                workflowEventPublisher.publishWorkflowUpdate(event);

                log.info(
                        "Workflow {} resumed (dag={}). Remaining incomplete tasks: {}",
                        updatedWorkflow.getName(),
                        isDagWorkflow,
                        remainingIncompleteCount
                );
            }

            return updatedWorkflow;
        } catch (Exception e) {
            if (workflowAdmissionController != null) {
                try {
                    workflowAdmissionController.release(workflow.getId(), workflow.getUserId());
                } catch (Exception releaseEx) {
                    log.error("Failed to release admission slot after resumeWorkflow failure for workflow {}: {}",
                            workflow.getId(), releaseEx.getMessage());
                }
            }
            throw e;
        }
    }

    @Transactional
    public Workflow retryWorkflow(Long workflowId) {
        return retryWorkflow(workflowId, UserContext.getUserId());
    }

    @Transactional
    public Workflow retryWorkflow(Long workflowId, Long userId) {
        Workflow workflow = getWorkflowForUpdate(workflowId, userId);

        if (workflow.getStatus() != WorkflowStatus.FAILED) {
            if (workflow.getStatus() == WorkflowStatus.RUNNING) {
                throw new IllegalStateException("Cannot retry workflow: workflow is already RUNNING");
            } else if (workflow.getStatus() == WorkflowStatus.PAUSED) {
                throw new IllegalStateException("Cannot retry workflow: workflow is PAUSED. Use resume instead.");
            } else if (workflow.getStatus() == WorkflowStatus.CREATED) {
                throw new IllegalStateException("Cannot retry workflow: workflow is in CREATED state. Use start instead.");
            } else {
                throw new IllegalStateException("Cannot retry workflow: workflow is in terminal state " + workflow.getStatus());
            }
        }

        WorkflowStateValidator.validateTransition(workflow.getStatus(), WorkflowStatus.RUNNING, "retry");

        if (workflowAdmissionController != null) {
            workflowAdmissionController.tryAdmit(workflow.getId(), workflow.getUserId());
        }

        try {
            long retryStartedAt = System.currentTimeMillis();
            workflow.setStatus(WorkflowStatus.RUNNING);
            workflow.setStartedAt(retryStartedAt);
            workflow.setCompletedAt(null);

            Workflow updatedWorkflow =
                    workflowRepository.save(workflow);

            List<WorkflowTask> workflowTasks =
                    workflowTaskRepository
                            .findByWorkflowIdOrderByExecutionOrder(workflowId);

            workflowTasks.forEach(task -> {
                task.setStatus(TaskStatus.PENDING);
                task.setStartedAt(null);
                task.setCompletedAt(null);
                task.setRetryCount(0);
                task.setOutputJson(null);
                task.setErrorMessage(null);
            });

            workflowTaskRepository.saveAll(workflowTasks);

            Optional<WorkflowExecution> priorExecutionOpt =
                    workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(workflowId);
            long nextExecutionGeneration = priorExecutionOpt
                    .map(WorkflowExecution::getExecutionGeneration)
                    .filter(Objects::nonNull)
                    .orElse(1L) + 1L;

            WorkflowExecution execution =
                    WorkflowExecution.builder()
                            .workflow(updatedWorkflow)
                            .status(WorkflowStatus.RUNNING)
                            .startedAt(retryStartedAt)
                            .lastCompletedExecutionOrder(null)
                            .executionGeneration(nextExecutionGeneration)
                            .build();

            WorkflowExecution savedExecution = workflowExecutionRepository.save(execution);
            if (savedExecution == null) {
                savedExecution = execution;
            }
            String retryEventId = UUID.randomUUID().toString();

            try (WorkflowTracingHelper.MdcScope ignored = WorkflowTracingHelper.scopedWorkflowLifecycle(
                    updatedWorkflow.getId(), savedExecution.getId(), nextExecutionGeneration, "FAILED->RUNNING", retryEventId)) {
                List<TaskExecutionRequest> taskRequests =
                        workflowTasks.stream()
                                .map(this::toTaskExecutionRequest)
                                .toList();

                executionRequestProducer.publishExecutionRequest(
                        WorkflowExecutionRequestEvent.builder()
                                .workflowId(updatedWorkflow.getId())
                                .workflowName(updatedWorkflow.getName())
                                .tasks(taskRequests)
                                .eventId(retryEventId)
                                .executionId(savedExecution.getId())
                                .resume(false)
                                .executionMode("RETRY")
                                .generation(nextExecutionGeneration)
                                .build()
                );

                WorkflowExecutionEvent event = WorkflowExecutionEvent.builder()
                        .workflowId(updatedWorkflow.getId())
                        .workflowName(updatedWorkflow.getName())
                        .status(updatedWorkflow.getStatus().name())
                        .userId(updatedWorkflow.getUserId())
                        .generation(nextExecutionGeneration)
                        .build();

                workflowEventPublisher.publishWorkflowUpdate(event);

                if (workflowMetrics != null) {
                    workflowMetrics.recordWorkflowRetried();
                    workflowMetrics.recordWorkflowStarted();
                }

                log.info(
                        "Workflow {} retry started with {} tasks",
                        updatedWorkflow.getName(),
                        taskRequests.size()
                );
            }

            return updatedWorkflow;
        } catch (Exception e) {
            if (workflowAdmissionController != null) {
                try {
                    workflowAdmissionController.release(workflow.getId(), workflow.getUserId());
                } catch (Exception releaseEx) {
                    log.error("Failed to release admission slot after retryWorkflow failure for workflow {}: {}",
                            workflow.getId(), releaseEx.getMessage());
                }
            }
            throw e;
        }
    }
}
