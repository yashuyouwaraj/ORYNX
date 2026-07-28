package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.execution.WorkflowExecution;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.kafka.ExecutionRequestProducer;
import com.orynx.orchestrator.kafka.KafkaProducer;
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


    public Workflow createWorkflow(CreateWorkflowRequest request){
        log.info("Creating workflow: {}",request.getName());

        Workflow workflow  = Workflow.builder()
                .name(request.getName())
                .scheduledAt(request.getScheduledAt())
                .scheduled(request.getScheduledAt() !=null)
                .goal(request.getGoal())
                .status(WorkflowStatus.CREATED)
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
    public Workflow startWorkflow(Long workflowId){
        Workflow workflow = workflowRepository.findById(workflowId).orElseThrow(()-> new RuntimeException("Workflow not found"));

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
        List<TaskExecutionRequest> taskRequests= workflowTasks.stream()
                        .map((task-> TaskExecutionRequest.builder()
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

    public Page<Workflow> getAllWorkflows(Pageable pageable){
        return workflowRepository.findAll(pageable);
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

    private void createWorkflowTasks(
            Workflow workflow
    ) {

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

    public Workflow getWorkflow(Long id){
        return workflowRepository.findById(id).orElseThrow(()-> new RuntimeException("Workflow not found: "+id));
    }

    public List<Workflow> getWorkflowsByStatus(WorkflowStatus status){
        return workflowRepository.findByStatusOrderByIdDesc(status);
    }

    public List<WorkflowTask> getWorkflowTasks(Long workflowId){
        return workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(workflowId);
    }

    public DashboardSummaryResponse getDashboardSummary(){
        return DashboardSummaryResponse.builder()
                .totalWorkflows(workflowRepository.count())
                .runningWorkflows(workflowRepository.countByStatus(WorkflowStatus.RUNNING))
                .completedWorkflows(workflowRepository.countByStatus(WorkflowStatus.COMPLETED))
                .failedWorkflows(workflowRepository.countByStatus(WorkflowStatus.FAILED))
                .createdWorkflows(workflowRepository.countByStatus(WorkflowStatus.CREATED))
                .build();
    }

    public List<WorkflowExecutionResponse> getWorkflowExecutionHistory(Long workflowId) {

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

        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new RuntimeException("Workflow not found"));

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

        double successRate =
                (successful * 100.0) / total;

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
}
