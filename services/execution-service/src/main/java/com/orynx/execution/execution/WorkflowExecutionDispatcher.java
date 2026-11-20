package com.orynx.execution.execution;

import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.coordination.StaleWorkerExecutionException;
import com.orynx.execution.worker.ExecutionWorkerProperties;
import com.orynx.execution.worker.WorkerQueueFullException;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
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

        log.info(
                "Dispatching workflow {} (id={}, gen={}) to worker pool [activeCount={}, queueSize={}]",
                workflowName,
                workflowId,
                effectiveGen,
                executor.getActiveCount(),
                executor.getThreadPoolExecutor().getQueue().size()
        );

        try {
            executor.submit(() -> {
                Thread currentThread = Thread.currentThread();
                activeExecutionTracker.register(workflowId, currentThread, effectiveGen, null);
                try {
                    coordinationService.markRunning(
                            workflowId,
                            effectiveGen,
                            instanceId,
                            properties.getLeaseDurationSeconds()
                    );
                    engine.execute(event, effectiveGen, instanceId);
                } catch (StaleWorkerExecutionException e) {
                    log.warn("Worker execution aborted because worker was fenced for workflow {} (gen={}): {}",
                            workflowId, effectiveGen, e.getMessage());
                } catch (Throwable t) {
                    log.error("Unhandled error in worker thread executing workflow {}", workflowId, t);
                    try {
                        coordinationService.markCompleted(workflowId, effectiveGen, instanceId, false);
                    } catch (Exception ignored) {}
                } finally {
                    activeExecutionTracker.unregister(workflowId);
                }
            });
        } catch (RejectedExecutionException e) {
            log.error(
                    "Worker pool capacity exhausted [active={}, queue={}] for workflow {} (id={})",
                    executor.getActiveCount(),
                    executor.getThreadPoolExecutor().getQueue().size(),
                    workflowName,
                    workflowId
            );
            throw new WorkerQueueFullException(
                    "Worker pool queue capacity exhausted for workflow " + workflowId,
                    e
            );
        }
    }
}
