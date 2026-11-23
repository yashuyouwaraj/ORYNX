package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.dto.WorkflowAnalyticsResponse;
import com.orynx.orchestrator.workflow.event.ExecutionEventRepository;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkflowAnalyticsService {
    private final WorkflowRepository workflowRepository;
    private final ExecutionEventRepository executionEventRepository;
    private final WorkflowTaskRepository workflowTaskRepository;

    public WorkflowAnalyticsResponse getAnalytics() {
        long eventCount = executionEventRepository.count();

        Map<WorkflowStatus, Long> counts = new EnumMap<>(WorkflowStatus.class);
        List<Object[]> grouped = workflowRepository.countGroupedByStatus();
        long totalWorkflows = 0L;
        if (grouped != null) {
            for (Object[] row : grouped) {
                if (row != null && row.length >= 2 && row[0] instanceof WorkflowStatus status && row[1] instanceof Number num) {
                    long count = num.longValue();
                    counts.put(status, count);
                    totalWorkflows += count;
                }
            }
        }

        long completedWorkflows = counts.getOrDefault(WorkflowStatus.COMPLETED, 0L);
        long failedWorkflows = counts.getOrDefault(WorkflowStatus.FAILED, 0L);
        long runningWorkflows = counts.getOrDefault(WorkflowStatus.RUNNING, 0L);

        double successRate = 0;
        double failureRate = 0;
        if (totalWorkflows > 0) {
            successRate = ((double) completedWorkflows / totalWorkflows) * 100;
            failureRate = ((double) failedWorkflows / totalWorkflows) * 100;
        }

        long totalTasks = workflowTaskRepository.count();
        long executedTaskCount = workflowTaskRepository.countByStatus(TaskStatus.COMPLETED);
        Double avgDuration = workflowTaskRepository.findAverageCompletedDurationMs();
        double averageExecutionTime = avgDuration != null ? avgDuration : 0;

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
