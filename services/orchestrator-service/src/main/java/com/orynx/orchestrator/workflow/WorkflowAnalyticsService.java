package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.dto.WorkflowAnalyticsResponse;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowAnalyticsService {
    private final WorkflowRepository workflowRepository;

    private final WorkflowTaskRepository workflowTaskRepository;

    public WorkflowAnalyticsResponse getAnalytics(){
        long totalWorkflows = workflowRepository.count();

        long completedWorkflows = workflowRepository.findAll()
                .stream()
                .filter(workflow -> workflow.getStatus() == WorkflowStatus.COMPLETED)
                .count();

        long runningWorkflows = workflowRepository.findAll()
                .stream()
                .filter(workflow -> workflow.getStatus()== WorkflowStatus.RUNNING)
                .count();

        List<WorkflowTask> tasks = workflowTaskRepository.findAll();

        long totalTasks = tasks.size();

        double averageExecutionTime = tasks.stream()
                .filter(task -> task.getStartedAt() !=null && task.getCompletedAt() !=null)
                .mapToLong(task-> task.getCompletedAt() - task.getStartedAt())
                .average()
                .orElse(0);

        return WorkflowAnalyticsResponse.builder()
                .totalWorkflows(totalWorkflows)
                .completedWorkflows(completedWorkflows)
                .runningWorkflows(runningWorkflows)
                .totalTasks(totalTasks)
                .averageExecutionTime(averageExecutionTime)
                .build();
    }
}
