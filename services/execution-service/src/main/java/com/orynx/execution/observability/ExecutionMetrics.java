package com.orynx.execution.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded operational metrics for execution-service worker pools, execution attempts,
 * retries, and fencing/recovery events.
 * Strictly avoids unbounded labels (never workflowId, executionId, traceId, or workerId).
 */
@Component
public class ExecutionMetrics {

    private final Counter attemptsSucceeded;
    private final Counter attemptsFailed;
    private final Counter taskRetries;
    private final Counter workerFenced;
    private final Counter workerRejected;
    private final Timer executionSuccessDuration;
    private final Timer executionFailureDuration;
    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    private final AtomicInteger queuedExecutions = new AtomicInteger(0);

    public ExecutionMetrics(MeterRegistry registry) {
        this.attemptsSucceeded = Counter.builder("orynx.execution.attempts")
                .tag("outcome", "SUCCESS")
                .description("Total task execution attempts completed successfully")
                .register(registry);
        this.attemptsFailed = Counter.builder("orynx.execution.attempts")
                .tag("outcome", "FAILED")
                .description("Total task execution attempts that failed")
                .register(registry);
        this.taskRetries = Counter.builder("orynx.execution.retries")
                .description("Total task retry attempts executed by execution-service")
                .register(registry);
        this.workerFenced = Counter.builder("orynx.execution.fenced")
                .description("Total workflow executions aborted due to stale worker fencing")
                .register(registry);
        this.workerRejected = Counter.builder("orynx.worker.pool.rejected")
                .description("Total workflow dispatches rejected due to worker pool saturation")
                .register(registry);

        this.executionSuccessDuration = Timer.builder("orynx.execution.duration")
                .tag("outcome", "COMPLETED")
                .description("Duration of workflow executions on execution-service workers")
                .publishPercentileHistogram(true)
                .serviceLevelObjectives(
                        Duration.ofMillis(100),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(120)
                )
                .register(registry);

        this.executionFailureDuration = Timer.builder("orynx.execution.duration")
                .tag("outcome", "FAILED")
                .description("Duration of failed workflow executions on execution-service workers")
                .publishPercentileHistogram(true)
                .serviceLevelObjectives(
                        Duration.ofMillis(100),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(120)
                )
                .register(registry);

        Gauge.builder("orynx.worker.pool.active", activeWorkers, AtomicInteger::get)
                .description("Currently active worker threads executing workflows")
                .register(registry);
        Gauge.builder("orynx.worker.pool.queue.size", queuedExecutions, AtomicInteger::get)
                .description("Current worker pool queue depth")
                .register(registry);
    }

    public void recordTaskResult(boolean success, int attempts) {
        if (success) {
            attemptsSucceeded.increment();
        } else {
            attemptsFailed.increment();
        }
        if (attempts > 1) {
            taskRetries.increment(attempts - 1);
        }
    }

    public void recordWorkerFenced() {
        workerFenced.increment();
    }

    public void recordWorkerRejected() {
        workerRejected.increment();
    }

    public void recordExecutionDuration(boolean success, long durationMs) {
        long safeMs = Math.max(0L, durationMs);
        if (success) {
            executionSuccessDuration.record(safeMs, TimeUnit.MILLISECONDS);
        } else {
            executionFailureDuration.record(safeMs, TimeUnit.MILLISECONDS);
        }
    }

    public void updatePoolStats(int active, int queued) {
        activeWorkers.set(Math.max(0, active));
        queuedExecutions.set(Math.max(0, queued));
    }
}
