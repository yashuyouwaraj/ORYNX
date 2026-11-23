package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.common.util.PaginationUtils;
import com.orynx.orchestrator.workflow.dto.WorkflowHealthResponse;
import com.orynx.orchestrator.workflow.dto.WorkflowPerformanceResponse;
import com.orynx.orchestrator.workflow.dto.WorkflowPerformanceSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowPerformanceService {
    private final WorkflowRepository workflowRepository;

    public List<WorkflowPerformanceResponse> getWorkflowPerformance() {
        return workflowRepository.findCompletedWorkflowsOrderByDurationAsc(
                        PageRequest.of(0, PaginationUtils.MAX_PAGE_SIZE))
                .stream()
                .map(workflow -> WorkflowPerformanceResponse.builder()
                        .workflowName(workflow.getName())
                        .durationMs(workflow.getCompletedAt() - workflow.getStartedAt())
                        .build())
                .toList();
    }

    public WorkflowPerformanceSummaryResponse getPerformanceSummary() {
        List<Object[]> aggregates = workflowRepository.findCompletedWorkflowDurationAggregates();
        if (aggregates == null || aggregates.isEmpty() || aggregates.get(0) == null) {
            return WorkflowPerformanceSummaryResponse.builder().build();
        }
        Object[] row = aggregates.get(0);
        long completedCount = row[0] instanceof Number n ? n.longValue() : 0L;
        if (completedCount == 0L) {
            return WorkflowPerformanceSummaryResponse.builder().build();
        }

        long fastestDuration = row[1] instanceof Number n ? n.longValue() : 0L;
        long slowestDuration = row[2] instanceof Number n ? n.longValue() : 0L;
        double average = row[3] instanceof Number n ? n.doubleValue() : 0.0;

        String fastestName = workflowRepository.findCompletedWorkflowsOrderByDurationAsc(PageRequest.of(0, 1))
                .stream().findFirst().map(Workflow::getName).orElse(null);
        String slowestName = workflowRepository.findCompletedWorkflowsOrderByDurationDesc(PageRequest.of(0, 1))
                .stream().findFirst().map(Workflow::getName).orElse(null);

        return WorkflowPerformanceSummaryResponse.builder()
                .fastestWorkflow(fastestName)
                .fastestDuration(fastestDuration)
                .slowestWorkflow(slowestName)
                .slowestDuration(slowestDuration)
                .averageDuration(average)
                .completedWorkflowCount(completedCount)
                .build();
    }

    public List<WorkflowHealthResponse> getWorkflowHealth() {
        List<Workflow> completed = workflowRepository.findCompletedWorkflowsOrderByDurationAsc(
                PageRequest.of(0, PaginationUtils.MAX_PAGE_SIZE));

        if (completed.isEmpty()) {
            return List.of();
        }

        double average = completed.stream()
                .mapToLong(workflow -> workflow.getCompletedAt() - workflow.getStartedAt())
                .average()
                .orElse(0);

        return completed.stream()
                .map(workflow -> {
                    long duration = workflow.getCompletedAt() - workflow.getStartedAt();

                    String health;
                    String reason;

                    if (duration <= average) {
                        health = "HEALTHY";
                        reason = "Execution is within expected duration";
                    } else if (duration <= average * 1.5) {
                        health = "WARNING";
                        reason = "Execution exceeded average duration.";
                    } else {
                        health = "CRITICAL";
                        reason = "Execution significantly exceeded average duration.";
                    }

                    return WorkflowHealthResponse.builder()
                            .workflowName(workflow.getName())
                            .durationMs(duration)
                            .health(health)
                            .reason(reason)
                            .build();
                })
                .toList();
    }
}
