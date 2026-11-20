package com.orynx.execution.execution;

import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.worker.ExecutionWorkerProperties;
import com.orynx.execution.worker.WorkerQueueFullException;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

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
        Long workflowId = event.getWorkflowId();
        String workflowName = event.getWorkflowName();

        log.info(
                "Dispatching workflow {} (id={}) to worker pool [activeCount={}, queueSize={}]",
                workflowName,
                workflowId,
                executor.getActiveCount(),
                executor.getThreadPoolExecutor().getQueue().size()
        );

        try {
            executor.submit(() -> {
                Thread currentThread = Thread.currentThread();
                activeExecutionTracker.register(workflowId, currentThread, null);
                try {
                    coordinationService.markRunning(
                            workflowId,
                            instanceId,
                            properties.getLeaseDurationSeconds()
                    );
                    engine.execute(event);
                } catch (Throwable t) {
                    log.error("Unhandled error in worker thread executing workflow {}", workflowId, t);
                    coordinationService.markCompleted(workflowId, false);
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
