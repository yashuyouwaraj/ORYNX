package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.kafka.KafkaProducer;
import com.orynx.orchestrator.workflow.dto.CreateWorkflowRequest;
import com.orynx.orchestrator.workflow.event.WorkflowCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowService {
    private final WorkflowRepository workflowRepository;
    private final KafkaProducer kafkaProducer;

    public Workflow createWorkflow(CreateWorkflowRequest request){
        log.info("Creating workflow: {}",request.getName());

        Workflow workflow  = Workflow.builder()
                .name(request.getName())
                .goal(request.getGoal())
                .status("CREATED")
                .build();

        Workflow savedWorkflow = workflowRepository.save(workflow);

        WorkflowCreatedEvent event = WorkflowCreatedEvent.builder()
                .workflowId(savedWorkflow.getId())
                .workflowName(savedWorkflow.getName())
                .goal(savedWorkflow.getGoal())
                .build();

        kafkaProducer.publishWorkflowCreatedEvent(event);

        return savedWorkflow;
    }
}
