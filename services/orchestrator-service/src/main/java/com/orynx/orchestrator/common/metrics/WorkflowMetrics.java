package com.orynx.orchestrator.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bounded-cardinality Micrometer metrics for OrynX workflow and task execution.
 * Pre-registers all expected bounded series at startup so Prometheus scrapes and
 * dashboard queries have a zero baseline immediately before the first workflow event.
 * Never attaches unbounded identifiers (userId, workflowId, taskId, executionId, requestId) as tags.
 */
@Component
public class WorkflowMetrics {

    private static final List<String> WORKFLOW_TERMINAL_STATUSES = List.of("COMPLETED", "FAILED", "CANCELLED");
    private static final List<String> WORKFLOW_DURATION_STATUSES = List.of("COMPLETED", "FAILED");
    private static final List<String> TASK_TERMINAL_STATUSES = List.of("COMPLETED", "FAILED");

    private final MeterRegistry meterRegistry;
    private final Counter workflowsCreated;
    private final Counter workflowsStarted;
    private final Counter workflowsRetried;
    private final Counter workflowsCancelled;

    public WorkflowMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.workflowsCreated = Counter.builder("orynx.workflows.created")
                .description("Total number of workflows created (standard and AI)")
                .register(meterRegistry);
        this.workflowsStarted = Counter.builder("orynx.workflows.started")
                .description("Total number of workflow executions started")
                .register(meterRegistry);
        this.workflowsRetried = Counter.builder("orynx.workflows.retried")
                .description("Total number of failed workflows retried")
                .register(meterRegistry);
        this.workflowsCancelled = Counter.builder("orynx.workflows.cancelled")
                .description("Total number of workflows cancelled")
                .register(meterRegistry);

        for (String status : WORKFLOW_TERMINAL_STATUSES) {
            completedCounter(status);
        }
        for (String status : WORKFLOW_DURATION_STATUSES) {
            durationTimer(status);
        }
        for (String status : TASK_TERMINAL_STATUSES) {
            taskCounter(status);
        }
    }

    public void recordWorkflowCreated() {
        workflowsCreated.increment();
    }

    public void recordWorkflowStarted() {
        workflowsStarted.increment();
    }

    public void recordWorkflowRetried() {
        workflowsRetried.increment();
    }

    public void recordWorkflowCancelled() {
        workflowsCancelled.increment();
        completedCounter("CANCELLED").increment();
    }

    public void recordWorkflowCompleted(String status, long durationMs) {
        String safeStatus = normalizeStatus(status);
        completedCounter(safeStatus).increment();
        if (durationMs >= 0 && !"CANCELLED".equals(safeStatus)) {
            durationTimer(safeStatus).record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    public void recordTaskExecuted(String status) {
        String safeStatus = normalizeStatus(status);
        taskCounter(safeStatus).increment();
    }

    private Counter completedCounter(String status) {
        return Counter.builder("orynx.workflows.completed")
                .tag("status", status)
                .description("Total number of workflows reaching terminal state")
                .register(meterRegistry);
    }

    private Timer durationTimer(String status) {
        return Timer.builder("orynx.workflows.duration")
                .tag("status", status)
                .description("Workflow execution duration for a single execution attempt")
                .publishPercentileHistogram(true)
                .minimumExpectedValue(Duration.ofMillis(100))
                .maximumExpectedValue(Duration.ofMinutes(15))
                .serviceLevelObjectives(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(45),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(120),
                        Duration.ofSeconds(300),
                        Duration.ofSeconds(600)
                )
                .register(meterRegistry);
    }

    private Counter taskCounter(String status) {
        return Counter.builder("orynx.tasks.executed")
                .tag("status", status)
                .description("Total number of distributed task execution completions/failures")
                .register(meterRegistry);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "UNKNOWN";
        }
        String upper = status.trim().toUpperCase();
        return switch (upper) {
            case "COMPLETED", "FAILED", "CANCELLED" -> upper;
            default -> "UNKNOWN";
        };
    }
}
