package com.orynx.orchestrator.ai;

import com.orynx.orchestrator.integration.planning.PlanningClient;
import com.orynx.orchestrator.ai.dto.AiWorkflowRequest;
import com.orynx.orchestrator.ai.dto.AiWorkflowResponse;
import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowEventProducer;
import com.orynx.orchestrator.workflow.WorkflowRepository;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import com.orynx.orchestrator.workflow.dto.WorkflowRealtimeEvent;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AiWorkflowService {
    private final PlanningClient planningClient;
    private final WorkflowRepository workflowRepository;
    private final WorkflowTaskRepository workflowTaskRepository;
    private final WorkflowEventProducer workflowEventProducer;

    public Workflow createdAiWorkflow(AiWorkflowRequest request){
        AiWorkflowResponse aiWorkflowResponse = planningClient.generateWorkflowPlan(request.getGoal());

        String workflowName = request.getName() != null && !request.getName().isBlank()
                ? request.getName()
                : aiWorkflowResponse.getWorkflowName();

        Workflow workflow = Workflow.builder()
                .name(workflowName)
                .goal(request.getGoal())
                .scheduledAt(request.getScheduledAt())
                .scheduled(request.getScheduledAt() != null)
                .status(WorkflowStatus.CREATED)
                .build();

        Workflow savedWorkflow = workflowRepository.save(workflow);

        workflowEventProducer.publishWorkflowEvent(WorkflowRealtimeEvent.builder()
                        .workflowId(savedWorkflow.getId())
                        .workflowName(savedWorkflow.getName())
                        .goal(savedWorkflow.getGoal())
                        .status(savedWorkflow.getStatus().name())
                        .build());

        List<String> tasks = aiWorkflowResponse.getTasks();

        for (int i = 0; i < tasks.size(); i++) {
            WorkflowTask task = WorkflowTask.builder()
                    .name(tasks.get(i))
                    .status(TaskStatus.PENDING)
                    .executionOrder(i+1)
                    .workflow(savedWorkflow)
                    .build();

            workflowTaskRepository.save(task);
        }

        return savedWorkflow;
    }
}
