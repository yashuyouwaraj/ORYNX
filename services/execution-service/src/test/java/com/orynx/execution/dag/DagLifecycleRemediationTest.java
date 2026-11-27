package com.orynx.execution.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.execution.checkpoint.TaskCheckpointService;
import com.orynx.execution.checkpoint.TaskCheckpointStatus;
import com.orynx.execution.checkpoint.WorkflowTaskCheckpoint;
import com.orynx.execution.checkpoint.WorkflowTaskCheckpointRepository;
import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.coordination.StaleWorkerExecutionException;
import com.orynx.execution.coordination.WorkflowExecutionCoordination;
import com.orynx.execution.event.dto.TaskCompletedEvent;
import com.orynx.execution.event.dto.TaskStartedEvent;
import com.orynx.execution.event.dto.WorkflowCompletedEvent;
import com.orynx.execution.execution.ActiveExecutionTracker;
import com.orynx.execution.execution.WorkflowCancellationRegistry;
import com.orynx.execution.execution.WorkflowExecutionDispatcher;
import com.orynx.execution.execution.WorkflowPauseRegistry;
import com.orynx.execution.handler.*;
import com.orynx.execution.kafka.ExecutionEventProducer;
import com.orynx.execution.recovery.ExecutionRecoveryScanner;
import com.orynx.execution.worker.ExecutionWorkerProperties;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.execution.workflow.event.dto.TaskExecutionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class DagLifecycleRemediationTest {

    private ThreadPoolTaskExecutor executor;
    private ExecutionEventProducer producer;
    private WorkflowCancellationRegistry cancellationRegistry;
    private WorkflowPauseRegistry pauseRegistry;
    private ExecutionCoordinationService coordinationService;
    private ActiveExecutionTracker tracker;
    private TaskHandlerRegistry registry;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("remediation-dag-");
        executor.initialize();

        producer = mock(ExecutionEventProducer.class);
        cancellationRegistry = new WorkflowCancellationRegistry();
        pauseRegistry = new WorkflowPauseRegistry();
        coordinationService = mock(ExecutionCoordinationService.class);
        when(coordinationService.isFenced(anyLong(), anyLong(), anyString())).thenReturn(false);
        tracker = new ActiveExecutionTracker();
        objectMapper = new ObjectMapper();
        registry = new TaskHandlerRegistry(List.of(
                new NoopTaskHandler(),
                new EchoTaskHandler(),
                new TransformTaskHandler(),
                new SimulatedTaskHandler()
        ));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // =========================================================================
    // S28-M-03: Scheduler Rejection Liveness & Shutdown
    // =========================================================================

    @Test
    void rejectionOfFinalReadyTaskSettlesCompletionFutureWithoutHanging() {
        ThreadPoolTaskExecutor rejectingExecutor = mock(ThreadPoolTaskExecutor.class);
        ThreadPoolExecutor rawPool = mock(ThreadPoolExecutor.class);
        when(rawPool.isShutdown()).thenReturn(false);
        when(rejectingExecutor.getThreadPoolExecutor()).thenReturn(rawPool);
        when(rejectingExecutor.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("queue full"));

        DagExecutionCoordinator coord = newCoordinator(rejectingExecutor, registry, null, new DagExecutionProperties());
        try {
            WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                    .workflowId(301L).workflowName("Reject-Single").generation(1L).executionMode("INITIAL")
                    .tasks(List.of(
                            TaskExecutionRequest.builder().taskKey("A").name("OnlyTask").taskType("NOOP").executionOrder(1).dependsOn(List.of()).build()
                    )).build();

            coord.executeDag(event, 1L, "worker-1");

            ArgumentCaptor<TaskCompletedEvent> taskCap = ArgumentCaptor.forClass(TaskCompletedEvent.class);
            verify(producer, times(1)).publishTaskCompleted(taskCap.capture());
            assertThat(taskCap.getValue().getTaskKey()).isEqualTo("A");
            assertThat(taskCap.getValue().isSuccess()).isFalse();
            assertThat(taskCap.getValue().getErrorMessage()).contains("saturated");

            ArgumentCaptor<WorkflowCompletedEvent> wfCap = ArgumentCaptor.forClass(WorkflowCompletedEvent.class);
            verify(producer, times(1)).publishWorkflowCompleted(wfCap.capture());
            assertThat(wfCap.getValue().isSuccess()).isFalse();
        } finally {
            coord.shutdownTimeoutScheduler();
        }
    }

    @Test
    void rejectionOfUpstreamTaskSkipsDownstreamAndSettlesCleanly() {
        ThreadPoolTaskExecutor rejectingExecutor = mock(ThreadPoolTaskExecutor.class);
        ThreadPoolExecutor rawPool = mock(ThreadPoolExecutor.class);
        when(rawPool.isShutdown()).thenReturn(false);
        when(rejectingExecutor.getThreadPoolExecutor()).thenReturn(rawPool);
        when(rejectingExecutor.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("queue full"));

        DagExecutionCoordinator coord = newCoordinator(rejectingExecutor, registry, null, new DagExecutionProperties());
        try {
            WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                    .workflowId(302L).workflowName("Reject-With-Downstream").generation(1L).executionMode("INITIAL")
                    .tasks(List.of(
                            TaskExecutionRequest.builder().taskKey("A").name("Root").taskType("NOOP").executionOrder(1).dependsOn(List.of()).build(),
                            TaskExecutionRequest.builder().taskKey("B").name("Child").taskType("NOOP").executionOrder(2).dependsOn(List.of("A")).build(),
                            TaskExecutionRequest.builder().taskKey("C").name("GrandChild").taskType("NOOP").executionOrder(3).dependsOn(List.of("B")).build()
                    )).build();

            coord.executeDag(event, 1L, "worker-1");

            ArgumentCaptor<TaskCompletedEvent> taskCap = ArgumentCaptor.forClass(TaskCompletedEvent.class);
            verify(producer, times(3)).publishTaskCompleted(taskCap.capture());
            Map<String, TaskCompletedEvent> byKey = mapByTaskKey(taskCap.getAllValues());
            assertThat(byKey.get("A").isSuccess()).isFalse();
            assertThat(byKey.get("B").isSkipped()).isTrue();
            assertThat(byKey.get("C").isSkipped()).isTrue();

            ArgumentCaptor<WorkflowCompletedEvent> wfCap = ArgumentCaptor.forClass(WorkflowCompletedEvent.class);
            verify(producer, times(1)).publishWorkflowCompleted(wfCap.capture());
            assertThat(wfCap.getValue().isSuccess()).isFalse();
        } finally {
            coord.shutdownTimeoutScheduler();
        }
    }

    @Test
    void rejectionDuringExecutorShutdownFailsAllQueuedTasksWithoutSilentLoss() {
        ThreadPoolTaskExecutor shutdownExecutor = mock(ThreadPoolTaskExecutor.class);
        ThreadPoolExecutor rawPool = mock(ThreadPoolExecutor.class);
        when(rawPool.isShutdown()).thenReturn(true);
        when(shutdownExecutor.getThreadPoolExecutor()).thenReturn(rawPool);
        when(shutdownExecutor.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("shutdown"));

        DagExecutionCoordinator coord = newCoordinator(shutdownExecutor, registry, null, new DagExecutionProperties());
        try {
            WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                    .workflowId(303L).workflowName("Shutdown-Multi-Root").generation(1L).executionMode("INITIAL")
                    .tasks(List.of(
                            TaskExecutionRequest.builder().taskKey("R1").name("Root1").taskType("NOOP").executionOrder(1).dependsOn(List.of()).build(),
                            TaskExecutionRequest.builder().taskKey("R2").name("Root2").taskType("NOOP").executionOrder(2).dependsOn(List.of()).build()
                    )).build();

            coord.executeDag(event, 1L, "worker-1");

            ArgumentCaptor<TaskCompletedEvent> taskCap = ArgumentCaptor.forClass(TaskCompletedEvent.class);
            verify(producer, times(2)).publishTaskCompleted(taskCap.capture());
            assertThat(taskCap.getAllValues()).allSatisfy(ev -> {
                assertThat(ev.isSuccess()).isFalse();
                assertThat(ev.getErrorMessage()).contains("shutting down");
            });
            verify(producer, times(1)).publishWorkflowCompleted(argThat(wf -> !wf.isSuccess()));
        } finally {
            coord.shutdownTimeoutScheduler();
        }
    }

    @Test
    void partialRejectionOfOneReadyTaskAllowsSiblingAndSettlesWorkflowFailed() {
        AtomicInteger submits = new AtomicInteger(0);
        ThreadPoolTaskExecutor partialRejectExecutor = mock(ThreadPoolTaskExecutor.class);
        ThreadPoolExecutor rawPool = mock(ThreadPoolExecutor.class);
        when(rawPool.isShutdown()).thenReturn(false);
        when(partialRejectExecutor.getThreadPoolExecutor()).thenReturn(rawPool);
        when(partialRejectExecutor.submit(any(Runnable.class))).thenAnswer(inv -> {
            Runnable r = inv.getArgument(0);
            if (submits.incrementAndGet() == 2) {
                throw new RejectedExecutionException("transient saturation");
            }
            return executor.submit(r);
        });

        DagExecutionCoordinator coord = newCoordinator(partialRejectExecutor, registry, null, new DagExecutionProperties());
        try {
            WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                    .workflowId(304L).workflowName("Partial-Reject").generation(1L).executionMode("INITIAL")
                    .tasks(List.of(
                            TaskExecutionRequest.builder().taskKey("R1").name("Root1").taskType("NOOP").executionOrder(1).dependsOn(List.of()).build(),
                            TaskExecutionRequest.builder().taskKey("R2").name("Root2").taskType("NOOP").executionOrder(2).dependsOn(List.of()).build()
                    )).build();

            coord.executeDag(event, 1L, "worker-1");

            ArgumentCaptor<TaskCompletedEvent> taskCap = ArgumentCaptor.forClass(TaskCompletedEvent.class);
            verify(producer, times(2)).publishTaskCompleted(taskCap.capture());
            Map<String, TaskCompletedEvent> byKey = mapByTaskKey(taskCap.getAllValues());
            assertThat(byKey.get("R1").isSuccess()).isTrue();
            assertThat(byKey.get("R2").isSuccess()).isFalse();
            verify(producer, times(1)).publishWorkflowCompleted(argThat(wf -> !wf.isSuccess()));
        } finally {
            coord.shutdownTimeoutScheduler();
        }
    }

    // =========================================================================
    // S28-M-02: Separate Retry from Crash Recovery
    // =========================================================================

    @Test
    void generation2UserRetryResetsPriorCompletedCheckpointsAndReexecutesAllTasks() {
        TaskCheckpointService cpService = mock(TaskCheckpointService.class);
        when(cpService.markTaskRunning(anyLong(), anyString(), anyString(), anyString(), any(), any(), anyLong(), anyString(), anyInt()))
                .thenReturn(true);

        DagExecutionCoordinator coord = newCoordinator(executor, registry, cpService, new DagExecutionProperties());
        try {
            WorkflowExecutionRequestEvent retryEvent = WorkflowExecutionRequestEvent.builder()
                    .workflowId(401L).workflowName("User-Retry-DAG").generation(2L)
                    .resume(false).executionMode("RETRY")
                    .tasks(List.of(
                            TaskExecutionRequest.builder().taskKey("A").name("Root").taskType("NOOP").executionOrder(1).dependsOn(List.of()).build(),
                            TaskExecutionRequest.builder().taskKey("B").name("Step2").taskType("NOOP").executionOrder(2).dependsOn(List.of("A")).build()
                    )).build();

            coord.executeDag(retryEvent, 2L, "worker-2");

            verify(cpService, times(1)).resetCheckpointsForFreshRun(401L, 2L);
            verify(cpService, never()).loadCheckpointsByTaskKey(anyLong());

            ArgumentCaptor<TaskStartedEvent> startedCap = ArgumentCaptor.forClass(TaskStartedEvent.class);
            verify(producer, times(2)).publishTaskStarted(startedCap.capture());
            assertThat(startedCap.getAllValues()).extracting(TaskStartedEvent::getTaskKey).containsExactly("A", "B");

            ArgumentCaptor<TaskCompletedEvent> completedCap = ArgumentCaptor.forClass(TaskCompletedEvent.class);
            verify(producer, times(2)).publishTaskCompleted(completedCap.capture());
            assertThat(completedCap.getAllValues()).extracting(TaskCompletedEvent::getTaskKey).containsExactly("A", "B");
            verify(producer).publishWorkflowCompleted(argThat(WorkflowCompletedEvent::isSuccess));
        } finally {
            coord.shutdownTimeoutScheduler();
        }
    }

    @Test
    void generation2CrashRecoveryPreservesCompletedCheckpointsAndSkipsCompletedUpstream() {
        TaskCheckpointService cpService = mock(TaskCheckpointService.class);
        WorkflowTaskCheckpoint cpA = WorkflowTaskCheckpoint.builder()
                .workflowId(402L).taskKey("A").status(TaskCheckpointStatus.COMPLETED)
                .executionGeneration(1L).outputJson("{\"val\":\"from-gen1\"}").updatedAt(Instant.now()).build();
        when(cpService.loadCheckpointsByTaskKey(402L)).thenReturn(Map.of("A", cpA));
        when(cpService.markTaskRunning(anyLong(), anyString(), anyString(), anyString(), any(), any(), anyLong(), anyString(), anyInt()))
                .thenReturn(true);

        DagExecutionCoordinator coord = newCoordinator(executor, registry, cpService, new DagExecutionProperties());
        try {
            WorkflowExecutionRequestEvent recoveryEvent = WorkflowExecutionRequestEvent.builder()
                    .workflowId(402L).workflowName("Crash-Recovery-DAG").generation(2L)
                    .resume(false).executionMode("RECOVERY")
                    .tasks(List.of(
                            TaskExecutionRequest.builder().taskKey("A").name("Root").taskType("NOOP").executionOrder(1).dependsOn(List.of()).build(),
                            TaskExecutionRequest.builder().taskKey("B").name("Consumer").taskType("TRANSFORM")
                                    .config(Map.of("operation", "MERGE_UPSTREAM")).executionOrder(2).dependsOn(List.of("A")).build()
                    )).build();

            coord.executeDag(recoveryEvent, 2L, "worker-2");

            verify(cpService, never()).resetCheckpointsForFreshRun(anyLong(), anyLong());
            verify(cpService, times(1)).loadCheckpointsByTaskKey(402L);

            ArgumentCaptor<TaskCompletedEvent> completedCap = ArgumentCaptor.forClass(TaskCompletedEvent.class);
            verify(producer, times(1)).publishTaskCompleted(completedCap.capture());
            assertThat(completedCap.getValue().getTaskKey()).isEqualTo("B");
            assertThat(completedCap.getValue().getOutputJson()).contains("from-gen1");
        } finally {
            coord.shutdownTimeoutScheduler();
        }
    }

    @Test
    void recoveryScannerSetsExplicitRecoveryExecutionModeOnReclaimedWorkflow() throws Exception {
        WorkflowExecutionDispatcher dispatcher = mock(WorkflowExecutionDispatcher.class);
        when(dispatcher.getInstanceId()).thenReturn("worker-rec");
        ExecutionWorkerProperties workerProps = new ExecutionWorkerProperties();
        ExecutionRecoveryScanner scanner = new ExecutionRecoveryScanner(
                coordinationService, dispatcher, producer, workerProps, objectMapper
        );

        String storedPayload = objectMapper.writeValueAsString(WorkflowExecutionRequestEvent.builder()
                .workflowId(403L).workflowName("Abandoned-WF").generation(1L).resume(false).executionMode("INITIAL")
                .tasks(List.of()).build());
        WorkflowExecutionCoordination reclaimed = WorkflowExecutionCoordination.builder()
                .workflowId(403L).workflowName("Abandoned-WF").generation(2L).payloadJson(storedPayload).build();

        when(coordinationService.reclaimForRecovery(eq(403L), eq("worker-rec"), anyLong(), anyInt()))
                .thenReturn(new ExecutionCoordinationService.RecoveryResult(
                        ExecutionCoordinationService.RecoveryOutcome.RECLAIMED, reclaimed));

        scanner.recoverExecution(reclaimed);

        ArgumentCaptor<WorkflowExecutionRequestEvent> reqCap = ArgumentCaptor.forClass(WorkflowExecutionRequestEvent.class);
        verify(dispatcher).dispatch(reqCap.capture(), eq(2L));
        assertThat(reqCap.getValue().getExecutionMode()).isEqualTo("RECOVERY");
    }

    // =========================================================================
    // S28-M-01: DAG-Aware Resume (Diamond with Out-of-Order Sibling)
    // =========================================================================

    @Test
    void dagResumeWithOutOfOrderCompletedSiblingExecutesIncompleteSiblingAndJoinExactlyOnce() {
        TaskCheckpointService cpService = mock(TaskCheckpointService.class);
        WorkflowTaskCheckpoint cpA = WorkflowTaskCheckpoint.builder()
                .workflowId(501L).taskKey("A").status(TaskCheckpointStatus.COMPLETED)
                .executionGeneration(1L).outputJson("{\"a\":\"alpha\"}").updatedAt(Instant.now()).build();
        WorkflowTaskCheckpoint cpC = WorkflowTaskCheckpoint.builder()
                .workflowId(501L).taskKey("C").status(TaskCheckpointStatus.COMPLETED)
                .executionGeneration(1L).outputJson("{\"c\":\"charlie\"}").updatedAt(Instant.now()).build();
        when(cpService.loadCheckpointsByTaskKey(501L)).thenReturn(Map.of("A", cpA, "C", cpC));
        when(cpService.markTaskRunning(anyLong(), anyString(), anyString(), anyString(), any(), any(), anyLong(), anyString(), anyInt()))
                .thenReturn(true);

        DagExecutionCoordinator coord = newCoordinator(executor, registry, cpService, new DagExecutionProperties());
        try {
            // C (order 3) completed BEFORE B (order 2). Resume sends full DAG + completedTaskKeys=[A, C].
            WorkflowExecutionRequestEvent resumeEvent = WorkflowExecutionRequestEvent.builder()
                    .workflowId(501L).workflowName("Diamond-Resume").generation(2L)
                    .resume(true).executionMode("RESUME").completedTaskKeys(List.of("A", "C"))
                    .tasks(List.of(
                            TaskExecutionRequest.builder().taskKey("A").name("Root").taskType("NOOP").executionOrder(1).dependsOn(List.of()).build(),
                            TaskExecutionRequest.builder().taskKey("B").name("Left").taskType("ECHO").config(Map.of("b", "bravo")).executionOrder(2).dependsOn(List.of("A")).build(),
                            TaskExecutionRequest.builder().taskKey("C").name("Right").taskType("NOOP").executionOrder(3).dependsOn(List.of("A")).build(),
                            TaskExecutionRequest.builder().taskKey("D").name("Join").taskType("TRANSFORM")
                                    .config(Map.of("operation", "MERGE_UPSTREAM")).executionOrder(4).dependsOn(List.of("B", "C")).build()
                    )).build();

            coord.executeDag(resumeEvent, 2L, "worker-2");

            ArgumentCaptor<TaskCompletedEvent> completedCap = ArgumentCaptor.forClass(TaskCompletedEvent.class);
            verify(producer, times(2)).publishTaskCompleted(completedCap.capture());
            List<TaskCompletedEvent> events = completedCap.getAllValues();
            assertThat(events).extracting(TaskCompletedEvent::getTaskKey).containsExactly("B", "D");
            assertThat(events.get(1).getOutputJson()).contains("charlie").contains("bravo");
            verify(producer).publishWorkflowCompleted(argThat(WorkflowCompletedEvent::isSuccess));
        } finally {
            coord.shutdownTimeoutScheduler();
        }
    }

    // =========================================================================
    // Section 6: Checkpoint First-Insert Race & Fencing
    // =========================================================================

    @Test
    void checkpointServiceHandlesFirstInsertRaceWithRowLockRetryAndFencing() {
        WorkflowTaskCheckpointRepository repo = mock(WorkflowTaskCheckpointRepository.class);
        TaskCheckpointService cpService = new TaskCheckpointService(repo);

        WorkflowTaskCheckpoint insertedByPeer = WorkflowTaskCheckpoint.builder()
                .workflowId(601L).taskKey("A").status(TaskCheckpointStatus.COMPLETED)
                .executionGeneration(2L).outputJson("{\"done\":true}").updatedAt(Instant.now()).build();

        // Attempt 1 sees empty -> saveAndFlush collides on unique constraint -> Attempt 2 locks peer's COMPLETED row
        when(repo.findByWorkflowIdAndTaskKeyForUpdate(601L, "A"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(insertedByPeer));
        when(repo.saveAndFlush(any(WorkflowTaskCheckpoint.class)))
                .thenThrow(new DataIntegrityViolationException("uk_wf_task_checkpoint"));

        boolean shouldRun = cpService.markTaskRunning(601L, "A", "A", "NOOP", 1, "[]", 2L, "w2", 1);
        assertThat(shouldRun).isFalse();
        verify(repo, times(2)).findByWorkflowIdAndTaskKeyForUpdate(601L, "A");

        // Stale gen 1 colliding with gen 2 row must be fenced on retry
        WorkflowTaskCheckpoint peerGen2 = WorkflowTaskCheckpoint.builder()
                .workflowId(602L).taskKey("A").status(TaskCheckpointStatus.RUNNING)
                .executionGeneration(2L).updatedAt(Instant.now()).build();
        when(repo.findByWorkflowIdAndTaskKeyForUpdate(602L, "A"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(peerGen2));

        assertThatThrownBy(() -> cpService.markTaskRunning(602L, "A", "A", "NOOP", 1, "[]", 1L, "w1", 1))
                .isInstanceOf(StaleWorkerExecutionException.class);
    }

    // =========================================================================
    // Section 7: Payload & Output Bounds
    // =========================================================================

    @Test
    void oversizedTaskConfigAndOversizedTaskOutputAreRejectedCleanly() {
        DagExecutionProperties tightProps = new DagExecutionProperties();
        tightProps.setMaxTaskConfigBytes(128);
        tightProps.setMaxTaskOutputBytes(128);

        TaskHandler hugeOutputHandler = new TaskHandler() {
            @Override
            public String getTaskType() {
                return "ECHO";
            }

            @Override
            public TaskHandlerResult execute(TaskHandlerContext context) {
                return TaskHandlerResult.ok(Map.of("blob", "x".repeat(500)));
            }
        };
        TaskHandlerRegistry customReg = new TaskHandlerRegistry(List.of(new NoopTaskHandler(), hugeOutputHandler));
        DagExecutionCoordinator coord = newCoordinator(executor, customReg, null, tightProps);
        try {
            // 1. Oversized config rejected before handler runs
            WorkflowExecutionRequestEvent bigCfgEvent = WorkflowExecutionRequestEvent.builder()
                    .workflowId(701L).workflowName("Big-Config").generation(1L).executionMode("INITIAL")
                    .tasks(List.of(
                            TaskExecutionRequest.builder().taskKey("A").name("BigCfg").taskType("NOOP")
                                    .config(Map.of("data", "y".repeat(300))).executionOrder(1).dependsOn(List.of()).build()
                    )).build();
            coord.executeDag(bigCfgEvent, 1L, "w1");
            verify(producer).publishTaskCompleted(argThat(ev ->
                    "A".equals(ev.getTaskKey()) && !ev.isSuccess() && ev.getErrorMessage().contains("config exceeds")));

            // 2. Oversized output rejected without committing COMPLETED
            WorkflowExecutionRequestEvent bigOutEvent = WorkflowExecutionRequestEvent.builder()
                    .workflowId(702L).workflowName("Big-Output").generation(1L).executionMode("INITIAL")
                    .tasks(List.of(
                            TaskExecutionRequest.builder().taskKey("B").name("BigOut").taskType("ECHO")
                                    .executionOrder(1).dependsOn(List.of()).build()
                    )).build();
            coord.executeDag(bigOutEvent, 1L, "w1");
            verify(producer).publishTaskCompleted(argThat(ev ->
                    "B".equals(ev.getTaskKey()) && !ev.isSuccess() && ev.getErrorMessage().contains("output exceeds")));
        } finally {
            coord.shutdownTimeoutScheduler();
        }
    }

    @Test
    void transformHandlerRejectsExcessiveConcatParts() {
        TransformTaskHandler handler = new TransformTaskHandler();
        List<String> tooManyParts = Collections.nCopies(TransformTaskHandler.DEFAULT_MAX_PARTS + 1, "a");
        TaskHandlerResult res = handler.execute(TaskHandlerContext.builder()
                .taskKey("T").config(Map.of("operation", "CONCAT", "parts", tooManyParts)).build());
        assertThat(res.isSuccess()).isFalse();
        assertThat(res.getErrorMessage()).contains("maximum parts limit");
    }

    // =========================================================================
    // Section 8: Non-Interruptible Timeout & Late Completion Rejection
    // =========================================================================

    @Test
    void nonInterruptibleHandlerTimesOutPromptlyAndLateCompletionCannotCommit() throws Exception {
        CountDownLatch releaseStuckHandler = new CountDownLatch(1);
        CountDownLatch handlerExited = new CountDownLatch(1);

        TaskHandler nonInterruptibleHandler = new TaskHandler() {
            @Override
            public String getTaskType() {
                return "NOOP";
            }

            @Override
            public TaskHandlerResult execute(TaskHandlerContext context) {
                // Deliberately ignore interrupts until test releases latch after workflow settles
                while (releaseStuckHandler.getCount() > 0) {
                    try {
                        releaseStuckHandler.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        // Simulate non-interruptible blocking I/O
                    }
                }
                handlerExited.countDown();
                return TaskHandlerResult.ok(Map.of("late", true));
            }
        };

        DagExecutionCoordinator coord = newCoordinator(
                executor, new TaskHandlerRegistry(List.of(nonInterruptibleHandler)), null, new DagExecutionProperties());
        try {
            WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                    .workflowId(801L).workflowName("NonInterruptible-Timeout").generation(1L).executionMode("INITIAL")
                    .tasks(List.of(
                            TaskExecutionRequest.builder().taskKey("T1").name("StuckTask").taskType("NOOP")
                                    .timeoutMs(120L).maxRetries(0).executionOrder(1).dependsOn(List.of()).build()
                    )).build();

            // Must return promptly after ~120ms even though handler is still blocked on latch
            coord.executeDag(event, 1L, "w1");

            // Now release the stuck handler and wait for it to finish returning late TaskHandlerResult.ok
            releaseStuckHandler.countDown();
            assertThat(handlerExited.await(3, TimeUnit.SECONDS)).isTrue();

            // Exactly ONE TaskCompletedEvent (FAILED/timed out) — late OK must NEVER commit
            ArgumentCaptor<TaskCompletedEvent> taskCap = ArgumentCaptor.forClass(TaskCompletedEvent.class);
            verify(producer, times(1)).publishTaskCompleted(taskCap.capture());
            assertThat(taskCap.getValue().isSuccess()).isFalse();
            assertThat(taskCap.getValue().getErrorMessage()).contains("timed out");
            verify(producer, times(1)).publishWorkflowCompleted(argThat(wf -> !wf.isSuccess()));
        } finally {
            releaseStuckHandler.countDown();
            coord.shutdownTimeoutScheduler();
        }
    }

    private DagExecutionCoordinator newCoordinator(
            ThreadPoolTaskExecutor exec,
            TaskHandlerRegistry reg,
            TaskCheckpointService cpService,
            DagExecutionProperties props
    ) {
        return new DagExecutionCoordinator(
                exec, props, reg, cpService, producer,
                cancellationRegistry, pauseRegistry, coordinationService, tracker, objectMapper
        );
    }

    private Map<String, TaskCompletedEvent> mapByTaskKey(List<TaskCompletedEvent> events) {
        Map<String, TaskCompletedEvent> map = new HashMap<>();
        for (TaskCompletedEvent ev : events) {
            map.put(ev.getTaskKey(), ev);
        }
        return map;
    }
}
