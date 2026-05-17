package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.kafka.KafkaProducer;
import com.orynx.orchestrator.workflow.dto.CreateWorkflowRequest;
import com.orynx.orchestrator.workflow.event.WorkflowCreatedEvent;
import com.orynx.orchestrator.workflow.event.WorkflowExecutionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowService {
    private final WorkflowRepository workflowRepository;
    private final KafkaProducer kafkaProducer;
    private final WorkflowEventPublisher workflowEventPublisher;

    public Workflow createWorkflow(CreateWorkflowRequest request){
        log.info("Creating workflow: {}",request.getName());

        Workflow workflow  = Workflow.builder()
                .name(request.getName())
                .goal(request.getGoal())
                .status(WorkflowStatus.CREATED)
                .build();

        Workflow savedWorkflow = workflowRepository.save(workflow);

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

    public Workflow startWorkflow(Long workflowId){
        Workflow workflow = workflowRepository.findById(workflowId).orElseThrow(()-> new RuntimeException("Workflow not found"));

        workflow.setStatus(WorkflowStatus.RUNNING);

        Workflow updatedWorkflow = workflowRepository.save(workflow);

        WorkflowExecutionEvent event = WorkflowExecutionEvent.builder()
                .workflowId(updatedWorkflow.getId())
                .workflowName(updatedWorkflow.getName())
                .status(updatedWorkflow.getStatus().name())
                .build();

        workflowEventPublisher.publishWorkflowUpdate(event);

        return updatedWorkflow;
    }
}
