package com.orynx.orchestrator.coordination.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded-cardinality Micrometer instrumentation for Redis-backed distributed coordination
 * and workflow admission control. Pre-registers all series at startup to guarantee baseline
 * metric availability. Never tags metrics with unbounded identifiers (userId, workflowId, lockToken).
 */
@Component
public class RedisCoordinationMetrics {

    private static final List<String> ADMISSION_STATUSES = List.of("accepted", "rejected", "deferred", "error");
    private static final List<String> ADMISSION_REASONS = List.of(
            "ok",
            "already_admitted",
            "global_limit_exceeded",
            "user_limit_exceeded",
            "redis_unavailable",
            "fail_open"
    );

    private static final List<String> REDIS_OPS = List.of("admit", "release", "lock_acquire", "lock_release", "reconcile", "health");
    private static final List<String> OP_STATUSES = List.of("success", "failure");

    private final MeterRegistry registry;
    private final AtomicLong activeGlobalWorkflows = new AtomicLong(0);
    private final AtomicLong admissionCapacityLimit = new AtomicLong(100);

    public RedisCoordinationMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Pre-register admission counters
        for (String status : ADMISSION_STATUSES) {
            for (String reason : ADMISSION_REASONS) {
                admissionCounter(status, reason);
            }
        }

        // Pre-register operation counters and timers
        for (String op : REDIS_OPS) {
            for (String st : OP_STATUSES) {
                opCounter(op, st);
            }
            opTimer(op);
        }

        // Active workflows gauge
        Gauge.builder("orynx.admission.active.workflows", activeGlobalWorkflows, AtomicLong::get)
                .description("Number of currently admitted active workflows across the cluster")
                .register(registry);

        // Admission capacity utilization gauge (ratio between active and maxGlobal)
        Gauge.builder("orynx.admission.utilization", this, RedisCoordinationMetrics::calculateUtilization)
                .description("Cluster workflow admission capacity utilization ratio (0.0 to 1.0)")
                .register(registry);
    }

    public void recordAdmission(String status, String reason) {
        admissionCounter(normalize(status), normalize(reason)).increment();
    }

    public void recordOperation(String operation, boolean success, long durationMs) {
        String safeOp = normalize(operation);
        String status = success ? "success" : "failure";
        opCounter(safeOp, status).increment();
        if (durationMs >= 0) {
            opTimer(safeOp).record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    public void updateActiveWorkflows(long count, long limit) {
        this.activeGlobalWorkflows.set(Math.max(0, count));
        if (limit > 0) {
            this.admissionCapacityLimit.set(limit);
        }
    }

    private double calculateUtilization() {
        long limit = admissionCapacityLimit.get();
        if (limit <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) activeGlobalWorkflows.get() / (double) limit);
    }

    private Counter admissionCounter(String status, String reason) {
        return Counter.builder("orynx.admission.requests.total")
                .tag("status", status)
                .tag("reason", reason)
                .description("Total number of workflow execution admission requests")
                .register(registry);
    }

    private Counter opCounter(String operation, String status) {
        return Counter.builder("orynx.redis.operations.total")
                .tag("operation", operation)
                .tag("status", status)
                .description("Total number of Redis distributed coordination operations")
                .register(registry);
    }

    private Timer opTimer(String operation) {
        return Timer.builder("orynx.redis.operation.duration")
                .tag("operation", operation)
                .description("Latency distribution of Redis distributed coordination calls")
                .publishPercentileHistogram(true)
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(registry);
    }

    private String normalize(String value) {
        return value == null ? "unknown" : value.toLowerCase().trim();
    }
}
