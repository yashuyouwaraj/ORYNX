package com.orynx.execution.dag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.execution.checkpoint.TaskCheckpointService;
import com.orynx.execution.checkpoint.TaskCheckpointStatus;
import com.orynx.execution.checkpoint.WorkflowTaskCheckpoint;
import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.coordination.StaleWorkerExecutionException;
import com.orynx.execution.event.dto.TaskCompletedEvent;
import com.orynx.execution.event.dto.TaskStartedEvent;
import com.orynx.execution.event.dto.WorkflowCompletedEvent;
import com.orynx.execution.event.dto.WorkflowExecutionPausedEvent;
import com.orynx.execution.execution.ActiveExecutionTracker;
import com.orynx.execution.execution.WorkflowCancellationRegistry;
import com.orynx.execution.execution.WorkflowPauseRegistry;
import com.orynx.execution.handler.TaskHandler;
import com.orynx.execution.handler.TaskHandlerContext;
import com.orynx.execution.handler.TaskHandlerRegistry;
import com.orynx.execution.handler.TaskHandlerResult;
import com.orynx.execution.kafka.ExecutionEventProducer;
import com.orynx.execution.observability.ExecutionKafkaTracePropagator;
import com.orynx.execution.observability.ExecutionMetrics;
import com.orynx.execution.observability.ExecutionObservabilitySanitizer;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.execution.workflow.event.dto.TaskExecutionRequest;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded, non-blocking frontier DAG execution coordinator.
 * Executes independent DAG tasks concurrently on a dedicated dagTaskExecutor (avoiding
 * worker-pool self-deadlock), enforces per-workflow and global concurrency bounds,
 * per-task timeouts, generation fencing, durable task checkpoints, crash recovery resume,
 * cooperative pause/cancellation, and deterministic downstream SKIPPED propagation.
 */
@Component
@Slf4j
public class DagExecutionCoordinator {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final int DEFAULT_MAX_CONFIG_BYTES = 16_384;
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 32_768;
    private static final int DEFAULT_MAX_NESTING_DEPTH = 8;

    public enum ExecutionMode {
        INITIAL,
        RETRY,
        RECOVERY,
        RESUME
    }

    private enum AttemptOutcome {
        RUNNING,
        COMPLETED,
        TIMED_OUT
    }

    private final ThreadPoolTaskExecutor dagTaskExecutor;
    private final DagExecutionProperties properties;
    private final TaskHandlerRegistry taskHandlerRegistry;
    private final TaskCheckpointService checkpointService;
    private final ExecutionEventProducer executionEventProducer;
    private final WorkflowCancellationRegistry cancellationRegistry;
    private final WorkflowPauseRegistry pauseRegistry;
    private final ExecutionCoordinationService coordinationService;
    private final ActiveExecutionTracker activeExecutionTracker;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService timeoutScheduler;
    private ExecutorService timeoutSettlementExecutor;

    @Autowired(required = false)
    private ExecutionMetrics executionMetrics;

    @Autowired
    public DagExecutionCoordinator(
            @Qualifier("dagTaskExecutor") ThreadPoolTaskExecutor dagTaskExecutor,
            DagExecutionProperties properties,
            TaskHandlerRegistry taskHandlerRegistry,
            TaskCheckpointService checkpointService,
            ExecutionEventProducer executionEventProducer,
            WorkflowCancellationRegistry cancellationRegistry,
            WorkflowPauseRegistry pauseRegistry,
            ExecutionCoordinationService coordinationService,
            ActiveExecutionTracker activeExecutionTracker,
            ObjectMapper objectMapper
    ) {
        this.dagTaskExecutor = dagTaskExecutor;
        this.properties = properties;
        this.taskHandlerRegistry = taskHandlerRegistry;
        this.checkpointService = checkpointService;
        this.executionEventProducer = executionEventProducer;
        this.cancellationRegistry = cancellationRegistry;
        this.pauseRegistry = pauseRegistry;
        this.coordinationService = coordinationService;
        this.activeExecutionTracker = activeExecutionTracker;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.timeoutScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "dag-task-timeout-watcher");
            t.setDaemon(true);
            return t;
        });
        AtomicInteger settlementSeq = new AtomicInteger(0);
        this.timeoutSettlementExecutor = new ThreadPoolExecutor(
                2,
                4,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "dag-timeout-settlement-" + settlementSeq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    void setTimeoutSettlementExecutorForTest(ExecutorService customExecutor) {
        ExecutorService old = this.timeoutSettlementExecutor;
        this.timeoutSettlementExecutor = customExecutor;
        if (old != null && old != customExecutor) {
            old.shutdownNow();
        }
    }

    @PreDestroy
    public void shutdownTimeoutScheduler() {
        timeoutScheduler.shutdownNow();
        if (timeoutSettlementExecutor != null) {
            timeoutSettlementExecutor.shutdownNow();
        }
    }

    public void executeDag(WorkflowExecutionRequestEvent event, Long generation, String instanceId) {
        Long workflowId = event.getWorkflowId();
        String workflowName = event.getWorkflowName();
        long effectiveGen = generation != null ? generation : 1L;
        long startMs = System.currentTimeMillis();
        String canonicalExecutionId = ExecutionKafkaTracePropagator.resolveCanonicalExecutionId(event, effectiveGen);
        List<TaskExecutionRequest> tasks = event.getTasks();

        if (tasks == null || tasks.isEmpty()) {
            completeWorkflowSuccess(workflowId, workflowName, effectiveGen, instanceId, startMs);
            return;
        }

        // 1. Build DAG node graph (preserving backward compatibility for legacy linear lists)
        DagGraph graph = buildGraph(tasks);

        // 2. Resolve explicit execution mode (NEVER infer crash recovery from generation > 1)
        ExecutionMode mode = resolveExecutionMode(event);
        Map<String, WorkflowTaskCheckpoint> existingCheckpoints = Collections.emptyMap();
        boolean preserveCheckpoints = (mode == ExecutionMode.RECOVERY || mode == ExecutionMode.RESUME);
        if (checkpointService != null) {
            if (preserveCheckpoints) {
                existingCheckpoints = checkpointService.loadCheckpointsByTaskKey(workflowId);
            } else {
                // INITIAL or RETRY: clear prior run checkpoints so user retries never silently
                // skip tasks that orchestrator-service reset to PENDING.
                checkpointService.resetCheckpointsForFreshRun(workflowId, effectiveGen);
            }
        }

        // 3. Reconstruct durable state from COMPLETED checkpoints (crash recovery / resume)
        Set<String> orchestratorCompletedKeys = (mode == ExecutionMode.RESUME && event.getCompletedTaskKeys() != null)
                ? new HashSet<>(event.getCompletedTaskKeys())
                : Collections.emptySet();
        Map<String, Map<String, Object>> outputsByTaskKey = new ConcurrentHashMap<>();
        int preCompletedCount = 0;
        AtomicInteger maxCompletedOrder = new AtomicInteger(0);

        for (DagNode node : graph.nodes.values()) {
            WorkflowTaskCheckpoint cp = existingCheckpoints.get(node.taskKey);
            boolean completedInCheckpoint = cp != null && cp.getStatus() == TaskCheckpointStatus.COMPLETED;
            boolean completedInResumeHint = preserveCheckpoints && orchestratorCompletedKeys.contains(node.taskKey);
            if (completedInCheckpoint || completedInResumeHint) {
                node.terminal.set(true);
                node.completedSuccess.set(true);
                node.dispatched.set(true);
                preCompletedCount++;
                if (node.executionOrder > maxCompletedOrder.get()) {
                    maxCompletedOrder.set(node.executionOrder);
                }
                if (completedInCheckpoint) {
                    Map<String, Object> parsedOut = parseOutputJson(cp.getOutputJson());
                    outputsByTaskKey.put(node.taskKey, parsedOut);
                }
                for (DagNode dep : node.dependents) {
                    dep.remainingDeps.decrementAndGet();
                }
            }
        }

        if (preCompletedCount > 0) {
            log.info("DAG {} for workflow {} (gen={}): skipping {} already-COMPLETED task(s)",
                    mode, workflowId, effectiveGen, preCompletedCount);
            if (executionMetrics != null) {
                executionMetrics.recordDagRecovery();
            }
        }

        int initialRemaining = graph.nodes.size() - preCompletedCount;
        if (initialRemaining <= 0) {
            completeWorkflowSuccess(workflowId, workflowName, effectiveGen, instanceId, startMs);
            return;
        }

        // 4. Initialize non-blocking frontier state
        int perWorkflowLimit = Math.max(1, properties != null ? properties.getMaxPerWorkflowConcurrency() : 4);
        DagRunState runState = new DagRunState(
                workflowId,
                workflowName,
                effectiveGen,
                instanceId,
                canonicalExecutionId,
                graph,
                outputsByTaskKey,
                initialRemaining,
                perWorkflowLimit,
                maxCompletedOrder,
                Context.current(),
                MDC.getCopyOfContextMap()
        );

        // Enqueue initially ready tasks (in-degree == 0 and not already COMPLETED)
        for (DagNode node : graph.nodes.values()) {
            if (!node.terminal.get() && node.remainingDeps.get() == 0) {
                if (node.dispatched.compareAndSet(false, true)) {
                    runState.readyQueue.add(node);
                }
            }
        }

        // Start non-blocking frontier dispatch
        drainReadyQueue(runState);

        // Wait for DAG completion on the coordinator thread (separate pool from dagTaskExecutor => zero deadlock)
        try {
            runState.completionFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            runState.interruptActiveTasks();
            return;
        } catch (ExecutionException e) {
            log.error("Unexpected DAG coordinator error for workflow {}: {}", workflowId, e.getMessage());
            runState.failed.set(true);
        }

        // 5. Finalize workflow outcome
        if (runState.fenced.get() || isFenced(workflowId, effectiveGen, instanceId)) {
            log.warn("Workflow {} (gen={}) fenced during DAG execution. Suppressing terminal events.",
                    workflowId, effectiveGen);
            return;
        }

        if (runState.cancelled.get() || isCancelled(workflowId)) {
            handleWorkflowCancelled(workflowId, effectiveGen, instanceId);
            return;
        }

        if (runState.paused.get() || isPaused(workflowId)) {
            Integer contiguousWatermark = computeContiguousCompletedOrder(runState);
            handleWorkflowPaused(workflowId, workflowName, effectiveGen, instanceId, contiguousWatermark);
            return;
        }

        if (runState.failed.get()) {
            completeWorkflowFailure(workflowId, workflowName, effectiveGen, instanceId, startMs);
            return;
        }

        completeWorkflowSuccess(workflowId, workflowName, effectiveGen, instanceId, startMs);
    }

    private void drainReadyQueue(DagRunState state) {
        while (true) {
            if (shouldStopScheduling(state)) {
                checkAllSettled(state);
                return;
            }
            int currentInFlight = state.inFlight.get();
            if (currentInFlight >= state.maxPerWorkflowConcurrency) {
                return;
            }
            DagNode next = state.readyQueue.poll();
            if (next == null) {
                checkAllSettled(state);
                return;
            }
            if (next.terminal.get()) {
                continue;
            }
            if (!state.inFlight.compareAndSet(currentInFlight, currentInFlight + 1)) {
                // Re-queue and retry CAS
                state.readyQueue.add(next);
                continue;
            }

            try {
                dagTaskExecutor.submit(() -> runDagNode(state, next));
            } catch (RejectedExecutionException rex) {
                state.inFlight.decrementAndGet();
                boolean executorShutdown = isExecutorShutDown();
                String reason = executorShutdown
                        ? "DAG task executor is shutting down"
                        : "DAG task executor queue saturated";
                log.warn("{} for workflow {} task {}: {}",
                        reason, state.workflowId, next.taskKey, rex.getMessage());
                if (executionMetrics != null) {
                    executionMetrics.recordWorkerRejected();
                }
                handleTaskFailure(state, next, 0, reason, 0L);

                if (executorShutdown) {
                    // Fail all remaining queued tasks explicitly so none are silently lost
                    DagNode queued;
                    while ((queued = state.readyQueue.poll()) != null) {
                        handleTaskFailure(state, queued, 0, reason, 0L);
                    }
                    checkAllSettled(state);
                    return;
                }

                // If active tasks remain in flight, their completion will re-invoke drainReadyQueue.
                // If no tasks are in flight, continue draining (bounded by <= graph.nodes.size()
                // because each rejected node becomes terminal) so no ready task is stranded and
                // completionFuture is guaranteed to settle.
                if (state.inFlight.get() > 0) {
                    checkAllSettled(state);
                    return;
                }
                // inFlight == 0: loop continues to either dispatch/fail remaining ready nodes or settle
            }
        }
    }

    private void runDagNode(DagRunState state, DagNode node) {
        Thread workerThread = Thread.currentThread();
        AtomicBoolean slotReleased = new AtomicBoolean(false);
        state.activeThreads.add(workerThread);
        if (executionMetrics != null) {
            executionMetrics.incrementActiveDagTasks();
        }
        Map<String, String> prevMdc = MDC.getCopyOfContextMap();
        if (state.callerMdc != null) {
            MDC.setContextMap(state.callerMdc);
        } else {
            MDC.clear();
        }
        MDC.put("workflowId", String.valueOf(state.workflowId));
        MDC.put("executionId", state.canonicalExecutionId);
        if (state.instanceId != null) {
            MDC.put("workerId", state.instanceId);
        }

        long taskStartMs = System.currentTimeMillis();
        Tracer tracer = GlobalOpenTelemetry.getTracer("com.orynx.execution.dag");
        Span taskSpan = tracer.spanBuilder("orynx.task.execute")
                .setParent(state.parentOtelContext)
                .setAttribute("orynx.workflow.id", String.valueOf(state.workflowId))
                .setAttribute("orynx.execution.id", state.canonicalExecutionId)
                .setAttribute("orynx.execution.generation", state.generation)
                .setAttribute("orynx.task.key", node.taskKey)
                .setAttribute("orynx.task.name", node.name)
                .setAttribute("orynx.task.type", node.taskType)
                .setAttribute("orynx.task.order", node.executionOrder)
                .startSpan();

        try (Scope ignored = taskSpan.makeCurrent()) {
            if (isFenced(state.workflowId, state.generation, state.instanceId)) {
                state.fenced.set(true);
                return;
            }
            if (isCancelled(state.workflowId)) {
                state.cancelled.set(true);
                markNodeCancelled(state, node);
                return;
            }
            if (isPaused(state.workflowId)) {
                state.paused.set(true);
                return;
            }

            // Validate task config payload bounds before running handler
            String configError = validateConfigBounds(node.config);
            if (configError != null) {
                taskSpan.setStatus(StatusCode.ERROR, configError);
                handleTaskFailure(state, node, 0, configError, System.currentTimeMillis() - taskStartMs);
                return;
            }

            // Durable RUNNING checkpoint (idempotent & generation-fenced)
            if (checkpointService != null) {
                boolean shouldRun = checkpointService.markTaskRunning(
                        state.workflowId,
                        node.taskKey,
                        node.name,
                        node.taskType,
                        node.executionOrder,
                        node.dependsOnJson,
                        state.generation,
                        state.instanceId,
                        1
                );
                if (!shouldRun) {
                    // Already COMPLETED in durable store
                    handleTaskSuccess(state, node, 1, Map.of(), System.currentTimeMillis() - taskStartMs);
                    return;
                }
            }

            executionEventProducer.publishTaskStarted(
                    TaskStartedEvent.builder()
                            .workflowId(state.workflowId)
                            .workflowName(state.workflowName)
                            .taskKey(node.taskKey)
                            .taskName(node.name)
                            .taskType(node.taskType)
                            .executionOrder(node.executionOrder)
                            .generation(state.generation)
                            .eventId("wf:" + state.workflowId + ":gen:" + state.generation + ":task-started:" + node.taskKey)
                            .build()
            );

            TaskHandler handler = taskHandlerRegistry.resolve(node.taskType);
            int maxAttempts = Math.max(1, node.maxRetries + 1);
            Map<String, Map<String, Object>> upstreamOutputs = collectUpstreamOutputs(state, node);
            TaskHandlerResult finalResult = TaskHandlerResult.failed("Task did not execute");
            int attemptsUsed = 0;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                attemptsUsed = attempt;
                MDC.put("attemptNumber", String.valueOf(attempt));
                taskSpan.setAttribute("orynx.task.attempt", attempt);

                if (node.terminal.get()) {
                    return;
                }
                if (isFenced(state.workflowId, state.generation, state.instanceId)) {
                    state.fenced.set(true);
                    return;
                }
                if (isCancelled(state.workflowId)) {
                    state.cancelled.set(true);
                    markNodeCancelled(state, node);
                    return;
                }

                TaskHandlerContext ctx = TaskHandlerContext.builder()
                        .workflowId(state.workflowId)
                        .executionId(state.canonicalExecutionId)
                        .generation(state.generation)
                        .taskKey(node.taskKey)
                        .taskName(node.name)
                        .taskType(node.taskType)
                        .attempt(attempt)
                        .timeoutMs(node.timeoutMs)
                        .config(node.config)
                        .upstreamOutputs(upstreamOutputs)
                        .cancellationCheck(() -> node.terminal.get()
                                || isCancelled(state.workflowId)
                                || isFenced(state.workflowId, state.generation, state.instanceId))
                        .build();

                boolean isFinalAttempt = (attempt == maxAttempts);
                finalResult = executeWithTimeout(
                        handler, ctx, workerThread, node.timeoutMs,
                        state, node, attempt, isFinalAttempt, taskStartMs, slotReleased
                );

                // If final-attempt timeout watcher already settled the node terminally, discard late result
                if (node.terminal.get()) {
                    return;
                }

                if (finalResult.isSuccess()) {
                    break;
                }

                if (isCancelled(state.workflowId)) {
                    state.cancelled.set(true);
                    markNodeCancelled(state, node);
                    return;
                }
                if (attempt < maxAttempts) {
                    log.warn("DAG task {}/{} failed on attempt {}/{}: {}. Retrying...",
                            state.workflowId, node.taskKey, attempt, maxAttempts, finalResult.getErrorMessage());
                }
            }

            if (node.terminal.get()) {
                return;
            }

            long durationMs = System.currentTimeMillis() - taskStartMs;
            taskSpan.setAttribute("orynx.task.attempts", attemptsUsed);
            taskSpan.setAttribute("orynx.task.success", finalResult.isSuccess());

            if (isFenced(state.workflowId, state.generation, state.instanceId)) {
                state.fenced.set(true);
                return;
            }

            if (executionMetrics != null) {
                executionMetrics.recordTaskResult(finalResult.isSuccess(), attemptsUsed);
            }

            if (finalResult.isSuccess()) {
                handleTaskSuccess(state, node, attemptsUsed, finalResult.getOutput(), durationMs);
            } else {
                taskSpan.setStatus(StatusCode.ERROR, finalResult.getErrorMessage());
                handleTaskFailure(state, node, attemptsUsed, finalResult.getErrorMessage(), durationMs);
            }
        } catch (StaleWorkerExecutionException stale) {
            state.fenced.set(true);
            taskSpan.setStatus(StatusCode.ERROR, "Fenced");
            if (executionMetrics != null) {
                executionMetrics.recordWorkerFenced();
            }
            log.warn("Fenced during DAG task {}/{}: {}", state.workflowId, node.taskKey, stale.getMessage());
        } catch (Throwable t) {
            if (!node.terminal.get()) {
                long durationMs = System.currentTimeMillis() - taskStartMs;
                taskSpan.recordException(t);
                taskSpan.setStatus(StatusCode.ERROR, ExecutionObservabilitySanitizer.safeErrorMessage(t));
                handleTaskFailure(state, node, 1, ExecutionObservabilitySanitizer.safeErrorMessage(t), durationMs);
            }
        } finally {
            taskSpan.end();
            state.activeThreads.remove(workerThread);
            if (executionMetrics != null) {
                executionMetrics.decrementActiveDagTasks();
            }
            if (prevMdc != null) {
                MDC.setContextMap(prevMdc);
            } else {
                MDC.clear();
            }
            if (slotReleased.compareAndSet(false, true)) {
                state.inFlight.decrementAndGet();
                drainReadyQueue(state);
            }
        }
    }

    private TaskHandlerResult executeWithTimeout(
            TaskHandler handler,
            TaskHandlerContext ctx,
            Thread workerThread,
            long timeoutMs,
            DagRunState state,
            DagNode node,
            int attempt,
            boolean isFinalAttempt,
            long taskStartMs,
            AtomicBoolean slotReleased
    ) {
        AtomicReference<AttemptOutcome> outcome = new AtomicReference<>(AttemptOutcome.RUNNING);
        ScheduledFuture<?> watcher = timeoutScheduler.schedule(() -> {
            if (outcome.compareAndSet(AttemptOutcome.RUNNING, AttemptOutcome.TIMED_OUT)) {
                workerThread.interrupt();
                if (executionMetrics != null) {
                    executionMetrics.recordDagTaskTimeout();
                }
                if (isFinalAttempt) {
                    // Offload blocking checkpoint/Kafka settlement off the lightweight timer thread
                    // onto a bounded settlement executor; if saturated/shut down, settle in-memory
                    // immediately so completionFuture can never hang.
                    try {
                        timeoutSettlementExecutor.execute(() ->
                                settleFinalAttemptTimeout(state, node, attempt, timeoutMs, taskStartMs, slotReleased));
                    } catch (RejectedExecutionException rex) {
                        log.error("Timeout settlement executor saturated/shut down for workflow {} task {}: {}",
                                state.workflowId, node.taskKey, rex.getMessage());
                        if (executionMetrics != null) {
                            executionMetrics.recordWorkerRejected();
                        }
                        state.failed.set(true);
                        if (node.terminal.compareAndSet(false, true)) {
                            state.remainingNodes.decrementAndGet();
                        }
                        if (slotReleased.compareAndSet(false, true)) {
                            state.inFlight.decrementAndGet();
                            drainReadyQueue(state);
                        }
                    }
                }
            }
        }, Math.max(50L, timeoutMs), TimeUnit.MILLISECONDS);

        try {
            TaskHandlerResult res = handler.execute(ctx);
            if (!outcome.compareAndSet(AttemptOutcome.RUNNING, AttemptOutcome.COMPLETED)) {
                // Timed out while executing — discard late result
                return TaskHandlerResult.failed("Task timed out after " + timeoutMs + "ms");
            }
            return res != null ? res : TaskHandlerResult.failed("TaskHandler returned null result");
        } catch (InterruptedException ie) {
            if (outcome.get() == AttemptOutcome.TIMED_OUT) {
                Thread.interrupted();
                return TaskHandlerResult.failed("Task timed out after " + timeoutMs + "ms");
            }
            outcome.compareAndSet(AttemptOutcome.RUNNING, AttemptOutcome.COMPLETED);
            Thread.currentThread().interrupt();
            return TaskHandlerResult.failed("Task interrupted");
        } catch (Exception ex) {
            if (outcome.get() == AttemptOutcome.TIMED_OUT) {
                return TaskHandlerResult.failed("Task timed out after " + timeoutMs + "ms");
            }
            outcome.compareAndSet(AttemptOutcome.RUNNING, AttemptOutcome.COMPLETED);
            return TaskHandlerResult.failed(ExecutionObservabilitySanitizer.safeErrorMessage(ex));
        } finally {
            watcher.cancel(false);
            if (outcome.get() == AttemptOutcome.TIMED_OUT) {
                Thread.interrupted();
            }
        }
    }

    private void settleFinalAttemptTimeout(
            DagRunState state,
            DagNode node,
            int attempt,
            long timeoutMs,
            long taskStartMs,
            AtomicBoolean slotReleased
    ) {
        try {
            if (state.fenced.get() || isFenced(state.workflowId, state.generation, state.instanceId)) {
                state.fenced.set(true);
                state.interruptActiveTasks();
                log.warn("Suppressing timeout durable settlement for fenced workflow {} (gen={}) task {}",
                        state.workflowId, state.generation, node.taskKey);
                return;
            }
            long elapsedMs = System.currentTimeMillis() - taskStartMs;
            handleTaskFailure(state, node, attempt, "Task timed out after " + timeoutMs + "ms", elapsedMs);
        } catch (StaleWorkerExecutionException stale) {
            state.fenced.set(true);
            state.interruptActiveTasks();
            if (executionMetrics != null) {
                executionMetrics.recordWorkerFenced();
            }
            log.warn("Fenced during timeout settlement for workflow {} (gen={}) task {}: {}",
                    state.workflowId, state.generation, node.taskKey, stale.getMessage());
        } catch (Throwable t) {
            state.failed.set(true);
            if (node.terminal.compareAndSet(false, true)) {
                state.remainingNodes.decrementAndGet();
            }
            log.error("Timeout durable settlement error for workflow {} task {}: {}",
                    state.workflowId, node.taskKey, ExecutionObservabilitySanitizer.safeErrorMessage(t));
        } finally {
            if (slotReleased.compareAndSet(false, true)) {
                state.inFlight.decrementAndGet();
                drainReadyQueue(state);
            }
        }
    }

    private void handleTaskSuccess(
            DagRunState state,
            DagNode node,
            int attempts,
            Map<String, Object> output,
            long durationMs
    ) {
        String outputJson = toJsonSafe(output);
        int maxOutputBytes = properties != null && properties.getMaxTaskOutputBytes() > 0
                ? properties.getMaxTaskOutputBytes()
                : DEFAULT_MAX_OUTPUT_BYTES;
        if (outputJson != null && outputJson.getBytes(StandardCharsets.UTF_8).length > maxOutputBytes) {
            handleTaskFailure(
                    state,
                    node,
                    attempts,
                    "Task output exceeds maximum serialized size of " + maxOutputBytes + " bytes",
                    durationMs
            );
            return;
        }
        if (!node.terminal.compareAndSet(false, true)) {
            return;
        }
        try {
            node.completedSuccess.set(true);
            if (output != null) {
                state.outputsByTaskKey.put(node.taskKey, output);
            }
            state.maxCompletedOrder.accumulateAndGet(node.executionOrder, Math::max);

            if (checkpointService != null) {
                checkpointService.markTaskTerminal(
                        state.workflowId, node.taskKey, node.name, node.taskType, node.executionOrder,
                        node.dependsOnJson, TaskCheckpointStatus.COMPLETED, state.generation, state.instanceId,
                        attempts, outputJson, null
                );
            }

            executionEventProducer.publishTaskCompleted(
                    TaskCompletedEvent.builder()
                            .workflowId(state.workflowId)
                            .workflowName(state.workflowName)
                            .taskKey(node.taskKey)
                            .taskName(node.name)
                            .taskType(node.taskType)
                            .executionOrder(node.executionOrder)
                            .success(true)
                            .attempts(attempts)
                            .generation(state.generation)
                            .eventId("wf:" + state.workflowId + ":gen:" + state.generation + ":task-completed:" + node.taskKey)
                            .outputJson(outputJson)
                            .build()
            );

            if (executionMetrics != null) {
                executionMetrics.recordDagTaskOutcome(node.taskType, "COMPLETED", durationMs);
            }

            // Atomically unlock eligible downstream fan-in dependents (exact-once dispatch)
            if (!shouldStopScheduling(state)) {
                for (DagNode dep : node.dependents) {
                    if (!dep.terminal.get() && dep.remainingDeps.decrementAndGet() == 0) {
                        if (dep.dispatched.compareAndSet(false, true)) {
                            state.readyQueue.add(dep);
                        }
                    }
                }
            }
        } finally {
            state.remainingNodes.decrementAndGet();
        }
    }

    private void handleTaskFailure(
            DagRunState state,
            DagNode node,
            int attempts,
            String errorMessage,
            long durationMs
    ) {
        if (!node.terminal.compareAndSet(false, true)) {
            return;
        }
        state.failed.set(true);
        try {
            if (checkpointService != null) {
                checkpointService.markTaskTerminal(
                        state.workflowId, node.taskKey, node.name, node.taskType, node.executionOrder,
                        node.dependsOnJson, TaskCheckpointStatus.FAILED, state.generation, state.instanceId,
                        attempts, null, errorMessage
                );
            }

            executionEventProducer.publishTaskCompleted(
                    TaskCompletedEvent.builder()
                            .workflowId(state.workflowId)
                            .workflowName(state.workflowName)
                            .taskKey(node.taskKey)
                            .taskName(node.name)
                            .taskType(node.taskType)
                            .executionOrder(node.executionOrder)
                            .success(false)
                            .attempts(attempts)
                            .generation(state.generation)
                            .eventId("wf:" + state.workflowId + ":gen:" + state.generation + ":task-completed:" + node.taskKey)
                            .errorMessage(errorMessage)
                            .build()
            );

            if (executionMetrics != null) {
                executionMetrics.recordDagTaskOutcome(node.taskType, "FAILED", durationMs);
            }
        } finally {
            state.remainingNodes.decrementAndGet();
        }

        // BFS propagate SKIPPED to all transitive downstream dependents
        ArrayDeque<DagNode> toSkip = new ArrayDeque<>(node.dependents);
        while (!toSkip.isEmpty()) {
            DagNode downstream = toSkip.poll();
            if (downstream.terminal.compareAndSet(false, true)) {
                downstream.dispatched.set(true);
                String skipReason = "Skipped due to failed upstream dependency: " + node.taskKey;
                try {
                    if (checkpointService != null) {
                        checkpointService.markTaskTerminal(
                                state.workflowId, downstream.taskKey, downstream.name, downstream.taskType,
                                downstream.executionOrder, downstream.dependsOnJson,
                                TaskCheckpointStatus.SKIPPED, state.generation, state.instanceId,
                                0, null, skipReason
                        );
                    }
                    executionEventProducer.publishTaskCompleted(
                            TaskCompletedEvent.builder()
                                    .workflowId(state.workflowId)
                                    .workflowName(state.workflowName)
                                    .taskKey(downstream.taskKey)
                                    .taskName(downstream.name)
                                    .taskType(downstream.taskType)
                                    .executionOrder(downstream.executionOrder)
                                    .success(false)
                                    .skipped(true)
                                    .attempts(0)
                                    .generation(state.generation)
                                    .eventId("wf:" + state.workflowId + ":gen:" + state.generation + ":task-skipped:" + downstream.taskKey)
                                    .errorMessage(skipReason)
                                    .build()
                    );
                    if (executionMetrics != null) {
                        executionMetrics.recordDagTaskOutcome(downstream.taskType, "SKIPPED", 0L);
                    }
                } finally {
                    state.remainingNodes.decrementAndGet();
                }
                toSkip.addAll(downstream.dependents);
            }
        }
    }

    private void markNodeCancelled(DagRunState state, DagNode node) {
        if (!node.terminal.compareAndSet(false, true)) {
            return;
        }
        try {
            if (checkpointService != null) {
                checkpointService.markTaskTerminal(
                        state.workflowId, node.taskKey, node.name, node.taskType, node.executionOrder,
                        node.dependsOnJson, TaskCheckpointStatus.CANCELLED, state.generation, state.instanceId,
                        0, null, "Workflow cancelled"
                );
            }
            executionEventProducer.publishTaskCompleted(
                    TaskCompletedEvent.builder()
                            .workflowId(state.workflowId)
                            .workflowName(state.workflowName)
                            .taskKey(node.taskKey)
                            .taskName(node.name)
                            .taskType(node.taskType)
                            .executionOrder(node.executionOrder)
                            .success(false)
                            .cancelled(true)
                            .attempts(0)
                            .generation(state.generation)
                            .eventId("wf:" + state.workflowId + ":gen:" + state.generation + ":task-cancelled:" + node.taskKey)
                            .errorMessage("Workflow cancelled")
                            .build()
            );
            if (executionMetrics != null) {
                executionMetrics.recordDagTaskOutcome(node.taskType, "CANCELLED", 0L);
            }
        } finally {
            state.remainingNodes.decrementAndGet();
        }
    }

    private boolean shouldStopScheduling(DagRunState state) {
        if (state.fenced.get() || isFenced(state.workflowId, state.generation, state.instanceId)) {
            state.fenced.set(true);
            state.interruptActiveTasks();
            return true;
        }
        if (state.cancelled.get() || isCancelled(state.workflowId)) {
            state.cancelled.set(true);
            state.interruptActiveTasks();
            return true;
        }
        if (state.paused.get() || isPaused(state.workflowId)) {
            state.paused.set(true);
            return true;
        }
        return false;
    }

    private void checkAllSettled(DagRunState state) {
        if (state.inFlight.get() == 0) {
            if (state.remainingNodes.get() <= 0 || state.fenced.get() || state.cancelled.get() || state.paused.get()
                    || state.readyQueue.isEmpty()) {
                state.completionFuture.complete(null);
            }
        }
    }

    private void completeWorkflowSuccess(Long workflowId, String workflowName, long generation, String instanceId, long startMs) {
        if (coordinationService != null) {
            if (instanceId != null) {
                coordinationService.markCompleted(workflowId, generation, instanceId, true);
            } else {
                coordinationService.markCompleted(workflowId, true);
            }
        }
        executionEventProducer.publishWorkflowCompleted(
                WorkflowCompletedEvent.builder()
                        .workflowId(workflowId)
                        .workflowName(workflowName)
                        .success(true)
                        .generation(generation)
                        .eventId("wf:" + workflowId + ":gen:" + generation + ":workflow-completed")
                        .build()
        );
        if (executionMetrics != null) {
            executionMetrics.recordExecutionDuration(true, System.currentTimeMillis() - startMs);
        }
    }

    private void completeWorkflowFailure(Long workflowId, String workflowName, long generation, String instanceId, long startMs) {
        if (coordinationService != null) {
            if (instanceId != null) {
                coordinationService.markCompleted(workflowId, generation, instanceId, false);
            } else {
                coordinationService.markCompleted(workflowId, false);
            }
        }
        executionEventProducer.publishWorkflowCompleted(
                WorkflowCompletedEvent.builder()
                        .workflowId(workflowId)
                        .workflowName(workflowName)
                        .success(false)
                        .generation(generation)
                        .eventId("wf:" + workflowId + ":gen:" + generation + ":workflow-completed")
                        .build()
        );
        if (executionMetrics != null) {
            executionMetrics.recordExecutionDuration(false, System.currentTimeMillis() - startMs);
        }
    }

    private void handleWorkflowCancelled(Long workflowId, long generation, String instanceId) {
        if (cancellationRegistry != null) {
            cancellationRegistry.clear(workflowId);
        }
        if (coordinationService != null) {
            coordinationService.markCancelled(workflowId);
        }
    }

    private void handleWorkflowPaused(Long workflowId, String workflowName, long generation, String instanceId, Integer lastOrder) {
        if (pauseRegistry != null) {
            pauseRegistry.clear(workflowId);
        }
        if (activeExecutionTracker != null) {
            activeExecutionTracker.clearPause(workflowId);
        }
        if (coordinationService != null) {
            if (instanceId != null) {
                coordinationService.markPaused(workflowId, generation, instanceId, lastOrder);
            } else {
                coordinationService.markPaused(workflowId, lastOrder);
            }
        }
        executionEventProducer.publishWorkflowExecutionPaused(
                WorkflowExecutionPausedEvent.builder()
                        .workflowId(workflowId)
                        .workflowName(workflowName)
                        .lastCompletedExecutionOrder(lastOrder)
                        .generation(generation)
                        .eventId("wf:" + workflowId + ":gen:" + generation + ":workflow-paused")
                        .build()
        );
    }

    private Map<String, Map<String, Object>> collectUpstreamOutputs(DagRunState state, DagNode node) {
        if (node.dependsOn.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String depKey : node.dependsOn) {
            Map<String, Object> out = state.outputsByTaskKey.get(depKey);
            if (out != null) {
                result.put(depKey, out);
            }
        }
        return result;
    }

    private DagGraph buildGraph(List<TaskExecutionRequest> tasks) {
        Map<String, DagNode> nodes = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(tasks.size());
        boolean hasExplicitDeps = tasks.stream().anyMatch(t -> t.getDependsOn() != null);

        for (int i = 0; i < tasks.size(); i++) {
            TaskExecutionRequest req = tasks.get(i);
            int order = req.getExecutionOrder() != null ? req.getExecutionOrder() : (i + 1);
            String key = (req.getTaskKey() != null && !req.getTaskKey().isBlank())
                    ? req.getTaskKey().trim()
                    : "task-" + order;
            String type = (req.getTaskType() != null && !req.getTaskType().isBlank())
                    ? req.getTaskType().trim().toUpperCase(Locale.ROOT)
                    : "NOOP";
            long timeoutMs = req.getTimeoutMs() != null && req.getTimeoutMs() > 0 ? req.getTimeoutMs() : DEFAULT_TIMEOUT_MS;
            int maxRetries = req.getMaxRetries() != null && req.getMaxRetries() >= 0 ? req.getMaxRetries() : 0;
            Map<String, Object> config = req.getConfig() != null ? req.getConfig() : Map.of();

            DagNode node = new DagNode(key, req.getName(), type, order, timeoutMs, maxRetries, config);
            nodes.put(key, node);
            keys.add(key);
        }

        for (int i = 0; i < tasks.size(); i++) {
            TaskExecutionRequest req = tasks.get(i);
            DagNode node = nodes.get(keys.get(i));
            List<String> deps;
            if (hasExplicitDeps) {
                deps = req.getDependsOn() != null ? req.getDependsOn() : List.of();
            } else {
                deps = (i == 0) ? List.of() : List.of(keys.get(i - 1));
            }
            List<String> validDeps = new ArrayList<>();
            for (String depKey : deps) {
                DagNode parent = nodes.get(depKey);
                if (parent != null) {
                    parent.dependents.add(node);
                    validDeps.add(depKey);
                }
            }
            node.dependsOn.addAll(validDeps);
            node.dependsOnJson = toJsonSafe(validDeps);
            node.remainingDeps.set(validDeps.size());
        }

        return new DagGraph(nodes);
    }

    ExecutionMode resolveExecutionMode(WorkflowExecutionRequestEvent event) {
        if (event != null && event.getExecutionMode() != null && !event.getExecutionMode().isBlank()) {
            try {
                return ExecutionMode.valueOf(event.getExecutionMode().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                log.warn("Unknown executionMode '{}' on workflow {}; falling back to resume flag",
                        event.getExecutionMode(), event.getWorkflowId());
            }
        }
        if (event != null && Boolean.TRUE.equals(event.getResume())) {
            return ExecutionMode.RESUME;
        }
        return ExecutionMode.INITIAL;
    }

    private boolean isExecutorShutDown() {
        try {
            return dagTaskExecutor.getThreadPoolExecutor().isShutdown();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private Integer computeContiguousCompletedOrder(DagRunState state) {
        List<DagNode> ordered = new ArrayList<>(state.graph.nodes.values());
        ordered.sort(Comparator.comparingInt(n -> n.executionOrder));
        Integer contiguous = null;
        for (DagNode n : ordered) {
            if (!n.completedSuccess.get()) {
                break;
            }
            contiguous = n.executionOrder;
        }
        return contiguous;
    }

    private String validateConfigBounds(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return null;
        }
        int maxDepth = properties != null && properties.getMaxPayloadNestingDepth() > 0
                ? properties.getMaxPayloadNestingDepth()
                : DEFAULT_MAX_NESTING_DEPTH;
        if (!isWithinNestingDepth(config, 1, maxDepth)) {
            return "Task config exceeds maximum nesting depth of " + maxDepth;
        }
        String serialized = toJsonSafe(config);
        int maxBytes = properties != null && properties.getMaxTaskConfigBytes() > 0
                ? properties.getMaxTaskConfigBytes()
                : DEFAULT_MAX_CONFIG_BYTES;
        if (serialized != null && serialized.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            return "Task config exceeds maximum serialized size of " + maxBytes + " bytes";
        }
        return null;
    }

    private boolean isWithinNestingDepth(Object value, int depth, int maxDepth) {
        if (depth > maxDepth) {
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                if (!isWithinNestingDepth(v, depth + 1, maxDepth)) {
                    return false;
                }
            }
        } else if (value instanceof Collection<?> col) {
            for (Object item : col) {
                if (!isWithinNestingDepth(item, depth + 1, maxDepth)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isFenced(Long workflowId, long generation, String instanceId) {
        if (activeExecutionTracker != null && activeExecutionTracker.isFenced(workflowId, generation)) {
            return true;
        }
        if (coordinationService != null && instanceId != null) {
            return coordinationService.isFenced(workflowId, generation, instanceId);
        }
        return false;
    }

    private boolean isCancelled(Long workflowId) {
        return (cancellationRegistry != null && cancellationRegistry.isCancelled(workflowId))
                || (activeExecutionTracker != null && activeExecutionTracker.isCancelled(workflowId))
                || (coordinationService != null && coordinationService.isCancelled(workflowId));
    }

    private boolean isPaused(Long workflowId) {
        return (pauseRegistry != null && pauseRegistry.isPaused(workflowId))
                || (activeExecutionTracker != null && activeExecutionTracker.isPaused(workflowId))
                || (coordinationService != null && coordinationService.isPaused(workflowId));
    }

    private String toJsonSafe(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseOutputJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static final class DagGraph {
        final Map<String, DagNode> nodes;

        DagGraph(Map<String, DagNode> nodes) {
            this.nodes = nodes;
        }
    }

    private static final class DagNode {
        final String taskKey;
        final String name;
        final String taskType;
        final int executionOrder;
        final long timeoutMs;
        final int maxRetries;
        final Map<String, Object> config;
        final List<String> dependsOn = new ArrayList<>();
        final List<DagNode> dependents = new ArrayList<>();
        final AtomicInteger remainingDeps = new AtomicInteger(0);
        final AtomicBoolean dispatched = new AtomicBoolean(false);
        final AtomicBoolean terminal = new AtomicBoolean(false);
        final AtomicBoolean completedSuccess = new AtomicBoolean(false);
        String dependsOnJson;

        DagNode(String taskKey, String name, String taskType, int executionOrder,
                long timeoutMs, int maxRetries, Map<String, Object> config) {
            this.taskKey = taskKey;
            this.name = name;
            this.taskType = taskType;
            this.executionOrder = executionOrder;
            this.timeoutMs = timeoutMs;
            this.maxRetries = maxRetries;
            this.config = config;
        }
    }

    private static final class DagRunState {
        final Long workflowId;
        final String workflowName;
        final long generation;
        final String instanceId;
        final String canonicalExecutionId;
        final DagGraph graph;
        final Map<String, Map<String, Object>> outputsByTaskKey;
        final AtomicInteger remainingNodes;
        final int maxPerWorkflowConcurrency;
        final AtomicInteger maxCompletedOrder;
        final Context parentOtelContext;
        final Map<String, String> callerMdc;

        final ConcurrentLinkedQueue<DagNode> readyQueue = new ConcurrentLinkedQueue<>();
        final AtomicInteger inFlight = new AtomicInteger(0);
        final Set<Thread> activeThreads = ConcurrentHashMap.newKeySet();
        final CompletableFuture<Void> completionFuture = new CompletableFuture<>();
        final AtomicBoolean failed = new AtomicBoolean(false);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean paused = new AtomicBoolean(false);
        final AtomicBoolean fenced = new AtomicBoolean(false);

        DagRunState(Long workflowId, String workflowName, long generation, String instanceId,
                    String canonicalExecutionId, DagGraph graph, Map<String, Map<String, Object>> outputsByTaskKey,
                    int initialRemaining, int maxPerWorkflowConcurrency, AtomicInteger maxCompletedOrder,
                    Context parentOtelContext, Map<String, String> callerMdc) {
            this.workflowId = workflowId;
            this.workflowName = workflowName;
            this.generation = generation;
            this.instanceId = instanceId;
            this.canonicalExecutionId = canonicalExecutionId;
            this.graph = graph;
            this.outputsByTaskKey = outputsByTaskKey;
            this.remainingNodes = new AtomicInteger(initialRemaining);
            this.maxPerWorkflowConcurrency = maxPerWorkflowConcurrency;
            this.maxCompletedOrder = maxCompletedOrder;
            this.parentOtelContext = parentOtelContext;
            this.callerMdc = callerMdc;
        }

        void interruptActiveTasks() {
            for (Thread t : activeThreads) {
                t.interrupt();
            }
        }
    }
}
