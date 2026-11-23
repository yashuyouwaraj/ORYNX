package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.common.exception.UnauthorizedException;
import com.orynx.orchestrator.common.security.UserContext;
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
        return getWorkflowPerformance(requireUserId());
    }

    public List<WorkflowPerformanceResponse> getWorkflowPerformance(Long userId) {
        if (userId == null) {
            throw new UnauthorizedException("Authenticated user context is required");
        }
        return workflowRepository.findCompletedWorkflowsByUserIdOrderByDurationAsc(
                        userId,
                        PageRequest.of(0, PaginationUtils.MAX_PAGE_SIZE))
                .stream()
                .map(workflow -> WorkflowPerformanceResponse.builder()
                        .workflowName(workflow.getName())
                        .durationMs(workflow.getCompletedAt() - workflow.getStartedAt())
                        .build())
                .toList();
    }

    public WorkflowPerformanceSummaryResponse getPerformanceSummary() {
        return getPerformanceSummary(requireUserId());
    }

    public WorkflowPerformanceSummaryResponse getPerformanceSummary(Long userId) {
        if (userId == null) {
            throw new UnauthorizedException("Authenticated user context is required");
        }
        List<Object[]> aggregates = workflowRepository.findCompletedWorkflowDurationAggregatesByUserId(userId);
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

        String fastestName = workflowRepository.findCompletedWorkflowsByUserIdOrderByDurationAsc(
                        userId, PageRequest.of(0, 1))
                .stream().findFirst().map(Workflow::getName).orElse(null);
        String slowestName = workflowRepository.findCompletedWorkflowsByUserIdOrderByDurationDesc(
                        userId, PageRequest.of(0, 1))
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
        return getWorkflowHealth(requireUserId());
    }

    public List<WorkflowHealthResponse> getWorkflowHealth(Long userId) {
        if (userId == null) {
            throw new UnauthorizedException("Authenticated user context is required");
        }
        List<Object[]> aggregates = workflowRepository.findCompletedWorkflowDurationAggregatesByUserId(userId);
        if (aggregates == null || aggregates.isEmpty() || aggregates.get(0) == null) {
            return List.of();
        }
        Object[] row = aggregates.get(0);
        long completedCount = row[0] instanceof Number n ? n.longValue() : 0L;
        if (completedCount == 0L) {
            return List.of();
        }
        double average = row[3] instanceof Number n ? n.doubleValue() : 0.0;

        List<Workflow> completed = workflowRepository.findCompletedWorkflowsByUserIdOrderByDurationAsc(
                userId,
                PageRequest.of(0, PaginationUtils.MAX_PAGE_SIZE));

        if (completed.isEmpty()) {
            return List.of();
        }

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

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("Authenticated user context is required");
        }
        return userId;
    }
}
