package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.kafka.KafkaProducer;
import com.orynx.orchestrator.workflow.dto.CreateWorkflowRequest;
import com.orynx.orchestrator.workflow.event.WorkflowCreatedEvent;
import com.orynx.orchestrator.workflow.event.WorkflowExecutionEvent;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowExecutionEngine;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowService {
    private final WorkflowRepository workflowRepository;
    private final KafkaProducer kafkaProducer;
    private final WorkflowEventPublisher workflowEventPublisher;
    private final WorkflowTaskRepository workflowTaskRepository;
    private final WorkflowExecutionEngine workflowExecutionEngine;

    public Workflow createWorkflow(CreateWorkflowRequest request){
        log.info("Creating workflow: {}",request.getName());

        Workflow workflow  = Workflow.builder()
                .name(request.getName())
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

        workflowExecutionEngine.executeWorkflow(workflowId);

        WorkflowExecutionEvent event = WorkflowExecutionEvent.builder()
                .workflowId(updatedWorkflow.getId())
                .workflowName(updatedWorkflow.getName())
                .status(updatedWorkflow.getStatus().name())
                .build();

        workflowEventPublisher.publishWorkflowUpdate(event);

        return updatedWorkflow;
    }

    public List<Workflow> getAllWorkflows(){
        return workflowRepository.findAllByOrderByIdDesc();
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
}
