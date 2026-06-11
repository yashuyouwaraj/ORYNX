package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.dto.WorkflowAnalyticsResponse;
import com.orynx.orchestrator.workflow.event.ExecutionEventRepository;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowAnalyticsService {
    private final WorkflowRepository workflowRepository;
    private final ExecutionEventRepository executionEventRepository;
    private final WorkflowTaskRepository workflowTaskRepository;

    public WorkflowAnalyticsResponse getAnalytics(){
        long eventCount = executionEventRepository.count();
        long totalWorkflows = workflowRepository.count();

        long completedWorkflows = workflowRepository.findAll()
                .stream()
                .filter(workflow -> workflow.getStatus() == WorkflowStatus.COMPLETED)
                .count();

        long failedWorkflows = workflowRepository.findAll()
                .stream()
                .filter(workflow -> workflow.getStatus() == WorkflowStatus.FAILED)
                .count();

        long runningWorkflows = workflowRepository.findAll()
                .stream()
                .filter(workflow -> workflow.getStatus()== WorkflowStatus.RUNNING)
                .count();

        double successRate = 0;

        double failureRate = 0;

        if(totalWorkflows > 0){
            successRate = ((double) completedWorkflows / totalWorkflows) *100;
            failureRate = ((double) failedWorkflows / totalWorkflows) *100;
        }

        List<WorkflowTask> tasks = workflowTaskRepository.findAll();

        long executedTaskCount = tasks.stream()
                .filter(task-> task.getStatus() == TaskStatus.COMPLETED)
                .count();

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
                .workflowSuccessRate(successRate)
                .workflowFailureRate(failureRate)
                .executedTaskCount(executedTaskCount)
                .eventCount(eventCount)
                .build();
    }
}
