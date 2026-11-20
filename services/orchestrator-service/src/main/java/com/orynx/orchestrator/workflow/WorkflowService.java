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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

        Workflow workflow = Workflow.builder()
                .name(request.getName())
                .scheduledAt(request.getScheduledAt())
                .scheduled(request.getScheduledAt() != null)
                .goal(request.getGoal())
                .status(WorkflowStatus.CREATED)
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
        Workflow workflow = getWorkflow(workflowId, userId);

        ensureWorkflowTasksExist(workflow);

        workflow.setStatus(WorkflowStatus.RUNNING);

        Workflow updatedWorkflow = workflowRepository.save(workflow);

        WorkflowExecution execution = WorkflowExecution.builder()
                .workflow(updatedWorkflow)
                .status(WorkflowStatus.RUNNING)
                .startedAt(System.currentTimeMillis())
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
        if (userId == null) {
            return workflowRepository.findAll(pageable);
        }
        return workflowRepository.findByUserIdOrderByIdDesc(userId, pageable);
    }

    private void ensureWorkflowTasksExist(Workflow workflow) {
        List<WorkflowTask> existingTasks =
                workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(workflow.getId());

        if (!existingTasks.isEmpty()) {
            return;
        }

        log.info("Creating default execution tasks for workflow: {}", workflow.getId());

        createWorkflowTasks(workflow);
    }

    private void createWorkflowTasks(Workflow workflow) {
        WorkflowTask collectMetrics =
                WorkflowTask.builder()
                        .name("Collect Metrics")
                        .status(TaskStatus.PENDING)
                        .executionOrder(1)
                        .workflow(workflow)
                        .build();

        WorkflowTask analyzeLogs =
                WorkflowTask.builder()
                        .name("Analyze Logs")
                        .status(TaskStatus.PENDING)
                        .executionOrder(2)
                        .workflow(workflow)
                        .build();

        WorkflowTask generateReport =
                WorkflowTask.builder()
                        .name("Generate Report")
                        .status(TaskStatus.PENDING)
                        .executionOrder(3)
                        .workflow(workflow)
                        .build();

        workflowTaskRepository.saveAll(
                List.of(
                        collectMetrics,
                        analyzeLogs,
                        generateReport
                )
        );
    }

    public Workflow getWorkflow(Long id) {
        return getWorkflow(id, UserContext.getUserId());
    }

    public Workflow getWorkflow(Long id, Long userId) {
        if (userId == null) {
            return workflowRepository.findById(id)
                    .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found: " + id));
        }
        return workflowRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found: " + id));
    }

    public List<Workflow> getWorkflowsByStatus(WorkflowStatus status) {
        return getWorkflowsByStatus(status, UserContext.getUserId());
    }

    public List<Workflow> getWorkflowsByStatus(WorkflowStatus status, Long userId) {
        if (userId == null) {
            return workflowRepository.findByStatusOrderByIdDesc(status);
        }
        return workflowRepository.findByUserIdAndStatusOrderByIdDesc(userId, status);
    }

    public List<WorkflowTask> getWorkflowTasks(Long workflowId) {
        return getWorkflowTasks(workflowId, UserContext.getUserId());
    }

    public List<WorkflowTask> getWorkflowTasks(Long workflowId, Long userId) {
        getWorkflow(workflowId, userId);
        return workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(workflowId);
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
        Workflow workflow = getWorkflow(workflowId, userId);

        if (workflow.getStatus() != WorkflowStatus.RUNNING) {
            throw new IllegalStateException("Only Running workflows can be cancelled");
        }

        workflow.setStatus(WorkflowStatus.CANCELLED);
        workflow.setCompletedAt(System.currentTimeMillis());
        Workflow updatedWorkflow = workflowRepository.save(workflow);

        workflowCancellationProducer.publishWorkflowCancelled(
                WorkflowCancelledEvent.builder()
                        .workflowId(workflow.getId())
                        .workflowName(workflow.getName())
                        .build()
        );

        Optional<WorkflowExecution> executionOptional = workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(workflowId);

        if (executionOptional.isPresent()) {
            WorkflowExecution execution = executionOptional.get();

            execution.setStatus(WorkflowStatus.CANCELLED);

            long completedTime = System.currentTimeMillis();

            execution.setCompletedAt(completedTime);

            execution.setDurationMs(completedTime - execution.getStartedAt());

            workflowExecutionRepository.save(execution);
        }

        log.info("Workflow {} cancelled.", updatedWorkflow.getName());

        return updatedWorkflow;
    }

    @Transactional
    public Workflow pauseWorkflow(Long workflowId) {
        return pauseWorkflow(workflowId, UserContext.getUserId());
    }

    @Transactional
    public Workflow pauseWorkflow(Long workflowId, Long userId) {
        Workflow workflow = getWorkflow(workflowId, userId);

        if (workflow.getStatus() != WorkflowStatus.RUNNING) {
            throw new IllegalStateException(
                    "Only RUNNING workflows can be paused."
            );
        }

        workflow.setStatus(WorkflowStatus.PAUSED);

        Workflow updatedWorkflow = workflowRepository.save(workflow);

        workflowPauseProducer.publishWorkflowPaused(
                WorkflowPausedEvent.builder()
                        .workflowId(updatedWorkflow.getId())
                        .workflowName(updatedWorkflow.getName())
                        .build()
        );

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
        Workflow workflow = getWorkflow(workflowId, userId);

        if (workflow.getStatus() != WorkflowStatus.PAUSED) {
            throw new IllegalStateException(
                    "Only PAUSED workflows can be resumed."
            );
        }

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

        workflow.setStatus(WorkflowStatus.RUNNING);

        Workflow updatedWorkflow =
                workflowRepository.save(workflow);

        execution.setStatus(WorkflowStatus.RUNNING);

        workflowExecutionRepository.save(execution);

        workflowResumeProducer.publishWorkflowResumed(
                WorkflowResumedEvent.builder()
                        .workflowId(updatedWorkflow.getId())
                        .workflowName(updatedWorkflow.getName())
                        .build()
        );

        executionRequestProducer.publishExecutionRequest(
                WorkflowExecutionRequestEvent.builder()
                        .workflowId(updatedWorkflow.getId())
                        .workflowName(updatedWorkflow.getName())
                        .tasks(remainingTasks)
                        .build()
        );

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
        Workflow workflow = getWorkflow(workflowId, userId);

        if (workflow.getStatus() != WorkflowStatus.FAILED) {
            throw new IllegalStateException(
                    "Only FAILED workflows can be retried."
            );
        }

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

        WorkflowExecution execution =
                WorkflowExecution.builder()
                        .workflow(updatedWorkflow)
                        .status(WorkflowStatus.RUNNING)
                        .startedAt(System.currentTimeMillis())
                        .lastCompletedExecutionOrder(null)
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
                        .build()
        );

        log.info(
                "Workflow {} retry started with {} tasks",
                updatedWorkflow.getName(),
                taskRequests.size()
        );

        return updatedWorkflow;
    }
}
