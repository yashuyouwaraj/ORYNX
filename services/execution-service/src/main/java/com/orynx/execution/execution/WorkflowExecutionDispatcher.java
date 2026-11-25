package com.orynx.execution.execution;

import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.coordination.StaleWorkerExecutionException;
import com.orynx.execution.observability.ExecutionKafkaTracePropagator;
import com.orynx.execution.observability.ExecutionMetrics;
import com.orynx.execution.observability.ExecutionObservabilitySanitizer;
import com.orynx.execution.worker.ExecutionWorkerProperties;
import com.orynx.execution.worker.WorkerQueueFullException;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class WorkflowExecutionDispatcher {

    private final ThreadPoolTaskExecutor executor;
    private final WorkflowExecutionEngine engine;
    private final ExecutionCoordinationService coordinationService;
    private final ActiveExecutionTracker activeExecutionTracker;
    private final ExecutionWorkerProperties properties;

    @Getter
    private final String instanceId;

    private final ScheduledExecutorService heartbeatScheduler;

    @Autowired(required = false)
    private ExecutionMetrics executionMetrics;

    public WorkflowExecutionDispatcher(
            @Qualifier("workflowExecutionExecutor") ThreadPoolTaskExecutor executor,
            WorkflowExecutionEngine engine,
            ExecutionCoordinationService coordinationService,
            ActiveExecutionTracker activeExecutionTracker,
            ExecutionWorkerProperties properties
    ) {
        this.executor = executor;
        this.engine = engine;
        this.coordinationService = coordinationService;
        this.activeExecutionTracker = activeExecutionTracker;
        this.properties = properties;
        this.instanceId = resolveInstanceId();
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "workflow-heartbeat-ticker");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void startHeartbeat() {
        long interval = Math.max(5L, properties.getHeartbeatIntervalSeconds());
        heartbeatScheduler.scheduleWithFixedDelay(
                this::performHeartbeats,
                interval,
                interval,
                TimeUnit.SECONDS
        );
        log.info("Started lease heartbeat scheduler with interval {}s on instance {}", interval, instanceId);
    }

    @PreDestroy
    public void shutdownHeartbeat() {
        heartbeatScheduler.shutdown();
        try {
            if (!heartbeatScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                heartbeatScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void performHeartbeats() {
        try {
            for (ActiveExecutionTracker.ActiveExecutionHandle handle : activeExecutionTracker.getActiveHandles()) {
                Long workflowId = handle.getWorkflowId();
                Long generation = handle.getGeneration();
                boolean ok = coordinationService.heartbeat(
                        workflowId,
                        generation,
                        instanceId,
                        properties.getLeaseDurationSeconds()
                );
                if (!ok) {
                    log.warn("Heartbeat rejected for workflow {} (gen={}) on instance {}. Fencing worker thread.",
                            workflowId, generation, instanceId);
                    handle.fence();
                }
            }
        } catch (Throwable t) {
            log.warn("Transient error during execution heartbeat tick: {}", t.getMessage());
        }
    }

    private String resolveInstanceId() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            return host + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception e) {
            return "exec-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    public void dispatch(WorkflowExecutionRequestEvent event) {
        dispatch(event, 1L);
    }

    public void dispatch(WorkflowExecutionRequestEvent event, Long generation) {
        Long workflowId = event.getWorkflowId();
        String workflowName = event.getWorkflowName();
        Long effectiveGen = generation != null ? generation : 1L;

        int activeCount = executor.getActiveCount();
        int queueSize = executor.getThreadPoolExecutor().getQueue().size();
        if (executionMetrics != null) {
            executionMetrics.updatePoolStats(activeCount, queueSize);
        }

        log.info(
                "Dispatching workflow {} (id={}, gen={}) to worker pool [activeCount={}, queueSize={}]",
                workflowName,
                workflowId,
                effectiveGen,
                activeCount,
                queueSize
        );

        // Capture caller OTel Context and MDC snapshot before crossing async worker pool boundary
        final Context parentContext = Context.current();
        final Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        final String executionId = ExecutionKafkaTracePropagator.resolveCanonicalExecutionId(event, effectiveGen);
        final String eventId = ExecutionObservabilitySanitizer.sanitizeCorrelationId(
                event.getEventId() != null ? event.getEventId() : MDC.get(ExecutionKafkaTracePropagator.MDC_EVENT_ID)
        );

        try {
            executor.submit(() -> {
                Thread currentThread = Thread.currentThread();
                if (callerMdc != null) {
                    MDC.setContextMap(callerMdc);
                } else {
                    MDC.clear();
                }
                if (workflowId != null) {
                    MDC.put("workflowId", String.valueOf(workflowId));
                }
                MDC.put("executionId", executionId);
                MDC.put("workerId", instanceId);
                if (eventId != null) {
                    MDC.put("eventId", eventId);
                }

                Tracer tracer = GlobalOpenTelemetry.getTracer("com.orynx.execution.worker");
                Span workerSpan = tracer.spanBuilder("orynx.worker.execute")
                        .setParent(parentContext)
                        .setSpanKind(SpanKind.INTERNAL)
                        .setAttribute("orynx.workflow.id", workflowId != null ? String.valueOf(workflowId) : "unknown")
                        .setAttribute("orynx.execution.id", executionId)
                        .setAttribute("orynx.execution.generation", effectiveGen)
                        .setAttribute("orynx.worker.id", instanceId)
                        .startSpan();
                if (eventId != null) {
                    workerSpan.setAttribute("orynx.event.id", eventId);
                }
                String corrId = MDC.get("correlationId");
                if (corrId != null) {
                    workerSpan.setAttribute("orynx.correlation.id", corrId);
                }
                if (workerSpan.getSpanContext().isValid()) {
                    MDC.put("trace_id", workerSpan.getSpanContext().getTraceId());
                    MDC.put("span_id", workerSpan.getSpanContext().getSpanId());
                }

                activeExecutionTracker.register(workflowId, currentThread, effectiveGen, null);
                try (Scope ignored = workerSpan.makeCurrent()) {
                    coordinationService.markRunning(
                            workflowId,
                            effectiveGen,
                            instanceId,
                            properties.getLeaseDurationSeconds()
                    );
                    engine.execute(event, effectiveGen, instanceId);
                } catch (StaleWorkerExecutionException e) {
                    String errCat = ExecutionObservabilitySanitizer.classifyErrorCategory(e);
                    MDC.put("errorCategory", errCat);
                    workerSpan.setAttribute("orynx.worker.fenced", true);
                    workerSpan.setAttribute("orynx.error.category", errCat);
                    if (executionMetrics != null) {
                        executionMetrics.recordWorkerFenced();
                    }
                    log.warn("Worker execution aborted because worker was fenced for workflow {} (gen={}): {}",
                            workflowId, effectiveGen, ExecutionObservabilitySanitizer.safeErrorMessage(e));
                } catch (Throwable t) {
                    String errCat = ExecutionObservabilitySanitizer.classifyErrorCategory(t);
                    MDC.put("errorCategory", errCat);
                    workerSpan.setStatus(StatusCode.ERROR, ExecutionObservabilitySanitizer.safeErrorMessage(t));
                    workerSpan.setAttribute("error.type", t.getClass().getSimpleName());
                    workerSpan.setAttribute("orynx.error.category", errCat);
                    log.error("Unhandled error in worker thread executing workflow {}", workflowId, t);
                    try {
                        coordinationService.markCompleted(workflowId, effectiveGen, instanceId, false);
                    } catch (Exception ignoredEx) {}
                } finally {
                    activeExecutionTracker.unregister(workflowId);
                    workerSpan.end();
                    if (executionMetrics != null) {
                        executionMetrics.updatePoolStats(
                                executor.getActiveCount(),
                                executor.getThreadPoolExecutor().getQueue().size()
                        );
                    }
                    // Unconditionally clear worker thread MDC to prevent cross-workflow contamination
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            if (executionMetrics != null) {
                executionMetrics.recordWorkerRejected();
            }
            String prevErrCat = MDC.get("errorCategory");
            MDC.put("errorCategory", "CAPACITY");
            try {
                log.error(
                        "Worker pool capacity exhausted [active={}, queue={}] for workflow {} (id={})",
                        executor.getActiveCount(),
                        executor.getThreadPoolExecutor().getQueue().size(),
                        workflowName,
                        workflowId
                );
            } finally {
                if (prevErrCat == null) {
                    MDC.remove("errorCategory");
                } else {
                    MDC.put("errorCategory", prevErrCat);
                }
            }
            throw new WorkerQueueFullException(
                    "Worker pool queue capacity exhausted for workflow " + workflowId,
                    e
            );
        }
    }
}
