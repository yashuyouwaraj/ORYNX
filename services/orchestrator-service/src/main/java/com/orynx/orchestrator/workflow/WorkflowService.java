package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.common.exception.WorkflowNotFoundException;
import com.orynx.orchestrator.common.security.UserContext;
import com.orynx.orchestrator.event.dto.WorkflowCancelledEvent;
import com.orynx.orchestrator.event.dto.WorkflowPausedEvent;
import com.orynx.orchestrator.event.dto.WorkflowResumedEvent;
import com.orynx.orchestrator.execution.WorkflowExecution;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.kafka.*;
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
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    public Workflow createWorkflow(CreateWorkflowRequest request) {
        return createWorkflow(request, UserContext.getUserId());
    }

    public Workflow createWorkflow(CreateWorkflowRequest request, Long userId) {
        log.info("Creating workflow: {} for userId: {}", request.getName(), userId);

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

        createWorkflowTasks(savedWorkflow);

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
                .build();

        workflowEventPublisher.publishWorkflowUpdate(executionEvent);

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

        workflowExecutionRepository.save(execution);

        log.info(
                "Created execution history record for workflow {}",
                updatedWorkflow.getName()
        );

        List<WorkflowTask> workflowTasks =
                workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(
                        workflow.getId()
                );
        List<TaskExecutionRequest> taskRequests = workflowTasks.stream()
                .map((task -> TaskExecutionRequest.builder()
                        .name(task.getName())
                        .executionOrder(task.getExecutionOrder())
                        .maxRetries(task.getMaxRetries())
                        .build()
                ))
                .toList();

        executionRequestProducer.publishExecutionRequest(
                WorkflowExecutionRequestEvent.builder()
                        .workflowId(workflow.getId())
                        .workflowName(workflow.getName())
                        .tasks(taskRequests)
                        .eventId(UUID.randomUUID().toString())
                        .executionId(execution.getId())
                        .resume(false)
                        .generation(1L)
                        .build()
        );

        WorkflowExecutionEvent event = WorkflowExecutionEvent.builder()
                .workflowId(updatedWorkflow.getId())
                .workflowName(updatedWorkflow.getName())
                .status(updatedWorkflow.getStatus().name())
                .build();

        workflowEventPublisher.publishWorkflowUpdate(event);

        return updatedWorkflow;
    }

    public Page<Workflow> getAllWorkflows(Pageable pageable) {
        return getAllWorkflows(pageable, UserContext.getUserId());
    }

    public Page<Workflow> getAllWorkflows(Pageable pageable, Long userId) {
        if (userId != null) {
            return workflowRepository.findByUserIdOrderByIdDesc(userId, pageable);
        } else {
            return workflowRepository.findAll(pageable);
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

    public List<WorkflowTask> getWorkflowTasks(Long workflowId) {
        return getWorkflowTasks(workflowId, UserContext.getUserId());
    }

    public List<WorkflowTask> getWorkflowTasks(Long workflowId, Long userId) {
        // Enforce ownership: caller must have access to workflow
        getWorkflow(workflowId, userId);
        return workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(workflowId);
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
        if (userId == null) {
            return DashboardSummaryResponse.builder()
                    .totalWorkflows(workflowRepository.count())
                    .runningWorkflows(workflowRepository.countByStatus(WorkflowStatus.RUNNING))
                    .pausedWorkflows(workflowRepository.countByStatus(WorkflowStatus.PAUSED))
                    .completedWorkflows(workflowRepository.countByStatus(WorkflowStatus.COMPLETED))
                    .failedWorkflows(workflowRepository.countByStatus(WorkflowStatus.FAILED))
                    .createdWorkflows(workflowRepository.countByStatus(WorkflowStatus.CREATED))
                    .cancelledWorkflows(workflowRepository.countByStatus(WorkflowStatus.CANCELLED))
                    .build();
        }

        return DashboardSummaryResponse.builder()
                .totalWorkflows(workflowRepository.countByUserId(userId))
                .runningWorkflows(workflowRepository.countByUserIdAndStatus(userId, WorkflowStatus.RUNNING))
                .pausedWorkflows(workflowRepository.countByUserIdAndStatus(userId, WorkflowStatus.PAUSED))
                .completedWorkflows(workflowRepository.countByUserIdAndStatus(userId, WorkflowStatus.COMPLETED))
                .failedWorkflows(workflowRepository.countByUserIdAndStatus(userId, WorkflowStatus.FAILED))
                .createdWorkflows(workflowRepository.countByUserIdAndStatus(userId, WorkflowStatus.CREATED))
                .cancelledWorkflows(workflowRepository.countByUserIdAndStatus(userId, WorkflowStatus.CANCELLED))
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

        workflowCancellationProducer.publishWorkflowCancelled(
                WorkflowCancelledEvent.builder()
                        .workflowId(workflow.getId())
                        .workflowName(workflow.getName())
                        .eventId(UUID.randomUUID().toString())
                        .build()
        );

        Optional<WorkflowExecution> executionOptional = workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(workflowId);

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
                .build();

        workflowEventPublisher.publishWorkflowUpdate(event);

        log.info("Workflow {} cancelled.", updatedWorkflow.getName());

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

        workflowPauseProducer.publishWorkflowPaused(
                WorkflowPausedEvent.builder()
                        .workflowId(updatedWorkflow.getId())
                        .workflowName(updatedWorkflow.getName())
                        .eventId(UUID.randomUUID().toString())
                        .build()
        );

        WorkflowExecutionEvent event = WorkflowExecutionEvent.builder()
                .workflowId(updatedWorkflow.getId())
                .workflowName(updatedWorkflow.getName())
                .status(updatedWorkflow.getStatus().name())
                .build();

        workflowEventPublisher.publishWorkflowUpdate(event);

        log.info(
                "Workflow {} paused.",
                updatedWorkflow.getName()
        );

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

        Integer lastCompletedOrder =
                execution.getLastCompletedExecutionOrder();

        final Integer resumeFromOrder =
                lastCompletedOrder == null ? 0 : lastCompletedOrder;

        List<WorkflowTask> workflowTasks =
                workflowTaskRepository
                        .findByWorkflowIdOrderByExecutionOrder(workflowId);

        List<TaskExecutionRequest> remainingTasks =
                workflowTasks.stream()
                        .filter(task ->
                                task.getExecutionOrder() > resumeFromOrder
                        )
                        .map(task ->
                                TaskExecutionRequest.builder()
                                        .name(task.getName())
                                        .executionOrder(task.getExecutionOrder())
                                        .maxRetries(task.getMaxRetries())
                                        .build()
                        )
                        .toList();

        if (remainingTasks.isEmpty()) {
            throw new IllegalStateException(
                    "No remaining tasks to resume for workflow " + workflowId
            );
        }

        long nextExecutionGeneration = (execution.getExecutionGeneration() != null
                ? execution.getExecutionGeneration()
                : 1L) + 1L;

        workflow.setStatus(WorkflowStatus.RUNNING);

        Workflow updatedWorkflow =
                workflowRepository.save(workflow);

        execution.setStatus(WorkflowStatus.RUNNING);
        execution.setExecutionGeneration(nextExecutionGeneration);

        workflowExecutionRepository.save(execution);

        workflowResumeProducer.publishWorkflowResumed(
                WorkflowResumedEvent.builder()
                        .workflowId(updatedWorkflow.getId())
                        .workflowName(updatedWorkflow.getName())
                        .eventId(UUID.randomUUID().toString())
                        .build()
        );

        executionRequestProducer.publishExecutionRequest(
                WorkflowExecutionRequestEvent.builder()
                        .workflowId(updatedWorkflow.getId())
                        .workflowName(updatedWorkflow.getName())
                        .tasks(remainingTasks)
                        .eventId(UUID.randomUUID().toString())
                        .executionId(execution.getId())
                        .resume(true)
                        .generation(nextExecutionGeneration)
                        .build()
        );

        WorkflowExecutionEvent event = WorkflowExecutionEvent.builder()
                .workflowId(updatedWorkflow.getId())
                .workflowName(updatedWorkflow.getName())
                .status(updatedWorkflow.getStatus().name())
                .build();

        workflowEventPublisher.publishWorkflowUpdate(event);

        log.info(
                "Workflow {} resumed from execution order {}. Remaining tasks: {}",
                updatedWorkflow.getName(),
                resumeFromOrder + 1,
                remainingTasks.size()
        );

        return updatedWorkflow;
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

        workflow.setStatus(WorkflowStatus.RUNNING);
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
                        .startedAt(System.currentTimeMillis())
                        .lastCompletedExecutionOrder(null)
                        .executionGeneration(nextExecutionGeneration)
                        .build();

        workflowExecutionRepository.save(execution);

        List<TaskExecutionRequest> taskRequests =
                workflowTasks.stream()
                        .map(task ->
                                TaskExecutionRequest.builder()
                                        .name(task.getName())
                                        .executionOrder(task.getExecutionOrder())
                                        .maxRetries(task.getMaxRetries())
                                        .build()
                        )
                        .toList();

        executionRequestProducer.publishExecutionRequest(
                WorkflowExecutionRequestEvent.builder()
                        .workflowId(updatedWorkflow.getId())
                        .workflowName(updatedWorkflow.getName())
                        .tasks(taskRequests)
                        .eventId(UUID.randomUUID().toString())
                        .executionId(execution.getId())
                        .resume(false)
                        .generation(nextExecutionGeneration)
                        .build()
        );

        WorkflowExecutionEvent event = WorkflowExecutionEvent.builder()
                .workflowId(updatedWorkflow.getId())
                .workflowName(updatedWorkflow.getName())
                .status(updatedWorkflow.getStatus().name())
                .build();

        workflowEventPublisher.publishWorkflowUpdate(event);

        log.info(
                "Workflow {} retry started with {} tasks",
                updatedWorkflow.getName(),
                taskRequests.size()
        );

        return updatedWorkflow;
    }
}
