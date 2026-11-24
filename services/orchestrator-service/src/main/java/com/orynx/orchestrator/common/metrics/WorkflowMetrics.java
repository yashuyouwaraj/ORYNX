package com.orynx.orchestrator.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Bounded-cardinality Micrometer metrics for OrynX workflow and task execution.
 * Never attaches unbounded identifiers (userId, workflowId, taskId, requestId) as tags.
 */
@Component
public class WorkflowMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter workflowsCreated;
    private final Counter workflowsStarted;

    public WorkflowMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.workflowsCreated = Counter.builder("orynx.workflows.created")
                .description("Total number of workflows created")
                .register(meterRegistry);
        this.workflowsStarted = Counter.builder("orynx.workflows.started")
                .description("Total number of workflows started")
                .register(meterRegistry);
    }

    public void recordWorkflowCreated() {
        workflowsCreated.increment();
    }

    public void recordWorkflowStarted() {
        workflowsStarted.increment();
    }

    public void recordWorkflowCompleted(String status, long durationMs) {
        String safeStatus = status != null ? status : "UNKNOWN";
        Counter.builder("orynx.workflows.completed")
                .tag("status", safeStatus)
                .description("Total number of workflows reaching terminal state")
                .register(meterRegistry)
                .increment();
        if (durationMs >= 0) {
            Timer.builder("orynx.workflows.duration")
                    .tag("status", safeStatus)
                    .description("Workflow execution duration")
                    .register(meterRegistry)
                    .record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    public void recordTaskExecuted(String status) {
        String safeStatus = status != null ? status : "UNKNOWN";
        Counter.builder("orynx.tasks.executed")
                .tag("status", safeStatus)
                .description("Total number of task execution completions/failures")
                .register(meterRegistry)
                .increment();
    }
}
