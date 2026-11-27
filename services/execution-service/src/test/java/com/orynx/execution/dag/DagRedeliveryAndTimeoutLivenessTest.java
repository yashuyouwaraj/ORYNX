package com.orynx.execution.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.execution.checkpoint.TaskCheckpointService;
import com.orynx.execution.checkpoint.TaskCheckpointStatus;
import com.orynx.execution.checkpoint.WorkflowTaskCheckpoint;
import com.orynx.execution.coordination.*;
import com.orynx.execution.event.dto.TaskCompletedEvent;
import com.orynx.execution.event.dto.WorkflowCompletedEvent;
import com.orynx.execution.execution.*;
import com.orynx.execution.handler.*;
import com.orynx.execution.kafka.ExecutionEventProducer;
import com.orynx.execution.kafka.ExecutionRequestConsumer;
import com.orynx.execution.worker.ExecutionWorkerProperties;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.execution.workflow.event.dto.TaskExecutionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class DagRedeliveryAndTimeoutLivenessTest {

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
        executor.setCorePoolSize(6);
        executor.setMaxPoolSize(12);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("remed2-dag-");
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
    // S28-R-M-01: Expired-Lease Reclaim via Kafka Redelivery -> RECOVERY Mode
    // =========================================================================

    @Test
    void coordinationServiceDistinguishesExpiredRunningAndDispatchedReclaimFromInitialRetryAndActiveLease() {
        WorkflowExecutionCoordinationRepository repo = mock(WorkflowExecutionCoordinationRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ExecutionCoordinationService service = new ExecutionCoordinationService(repo);

        // 1. Fresh INITIAL -> reclaimedExpiredLease == false
        when(repo.findByWorkflowIdForUpdate(1L)).thenReturn(Optional.empty());
        ExecutionLeaseAcquisitionResult fresh = service.tryAcquireLease(1L, "WF1", "w1", 300, "{}", false, 1L);
        assertThat(fresh.isAcquired()).isTrue();
        assertThat(fresh.isReclaimedExpiredLease()).isFalse();

        // 2. Expired RUNNING lease -> reclaimedExpiredLease == true, generation bumped
        WorkflowExecutionCoordination expiredRunning = WorkflowExecutionCoordination.builder()
                .workflowId(2L).workflowName("WF2").status(ExecutionCoordinationStatus.RUNNING)
                .instanceId("dead-w1").generation(1L).leaseExpiresAt(Instant.now().minusSeconds(60)).build();
        when(repo.findByWorkflowIdForUpdate(2L)).thenReturn(Optional.of(expiredRunning));
        ExecutionLeaseAcquisitionResult recRunning = service.tryAcquireLease(2L, "WF2", "w2", 300, "{}", false, 1L);
        assertThat(recRunning.isAcquired()).isTrue();
        assertThat(recRunning.isReclaimedExpiredLease()).isTrue();
        assertThat(recRunning.getGeneration()).isEqualTo(2L);

        // 3. Expired DISPATCHED lease -> reclaimedExpiredLease == true
        WorkflowExecutionCoordination expiredDispatched = WorkflowExecutionCoordination.builder()
                .workflowId(3L).workflowName("WF3").status(ExecutionCoordinationStatus.DISPATCHED)
                .instanceId("dead-w1").generation(1L).leaseExpiresAt(Instant.now().minusSeconds(30)).build();
        when(repo.findByWorkflowIdForUpdate(3L)).thenReturn(Optional.of(expiredDispatched));
        ExecutionLeaseAcquisitionResult recDisp = service.tryAcquireLease(3L, "WF3", "w2", 300, "{}", false, 1L);
        assertThat(recDisp.isAcquired()).isTrue();
        assertThat(recDisp.isReclaimedExpiredLease()).isTrue();

        // 4. User RETRY from FAILED -> reclaimedExpiredLease == false (fresh retry!)
        WorkflowExecutionCoordination failed = WorkflowExecutionCoordination.builder()
                .workflowId(4L).workflowName("WF4").status(ExecutionCoordinationStatus.FAILED)
                .instanceId("w1").generation(1L).build();
        when(repo.findByWorkflowIdForUpdate(4L)).thenReturn(Optional.of(failed));
        ExecutionLeaseAcquisitionResult retryLease = service.tryAcquireLease(4L, "WF4", "w2", 300, "{}", false, 2L);
        assertThat(retryLease.isAcquired()).isTrue();
        assertThat(retryLease.isReclaimedExpiredLease()).isFalse();

        // 5. Duplicate delivery with active lease -> DUPLICATE_ACTIVE (not acquired)
        WorkflowExecutionCoordination active = WorkflowExecutionCoordination.builder()
                .workflowId(5L).workflowName("WF5").status(ExecutionCoordinationStatus.RUNNING)
                .instanceId("w1").generation(1L).leaseExpiresAt(Instant.now().plusSeconds(300)).build();
        when(repo.findByWorkflowIdForUpdate(5L)).thenReturn(Optional.of(active));
        ExecutionLeaseAcquisitionResult dup = service.tryAcquireLease(5L, "WF5", "w2", 300, "{}", false, 1L);
        assertThat(dup.isAcquired()).isFalse();
        assertThat(dup.getStatus()).isEqualTo(ExecutionLeaseAcquisitionResult.Status.DUPLICATE_ACTIVE);

        // 6. Already COMPLETED workflow -> DUPLICATE_TERMINAL (not acquired)
        WorkflowExecutionCoordination completed = WorkflowExecutionCoordination.builder()
                .workflowId(6L).workflowName("WF6").status(ExecutionCoordinationStatus.COMPLETED)
                .instanceId("w1").generation(1L).build();
        when(repo.findByWorkflowIdForUpdate(6L)).thenReturn(Optional.of(completed));
        ExecutionLeaseAcquisitionResult term = service.tryAcquireLease(6L, "WF6", "w2", 300, "{}", false, 1L);
        assertThat(term.isAcquired()).isFalse();
        assertThat(term.getStatus()).isEqualTo(ExecutionLeaseAcquisitionResult.Status.DUPLICATE_TERMINAL);
    }

    @Test
    void kafkaRedeliveryAfterExpiredLeaseReclaimSwitchesInitialAndRetryToRecoveryAndPreservesCheckpoints() {
        ExecutionService execService = mock(ExecutionService.class);
        WorkflowExecutionDispatcher dispatcher = mock(WorkflowExecutionDispatcher.class);
        when(dispatcher.getInstanceId()).thenReturn("worker-2");
        ExecutionWorkerProperties workerProps = new ExecutionWorkerProperties();
        Acknowledgment ack = mock(Acknowledgment.class);

        ExecutionRequestConsumer consumer = new ExecutionRequestConsumer(
                execService, coordinationService, dispatcher, workerProps, objectMapper, pauseRegistry, tracker
        );

        // Simulate expired-lease reclaim for a redelivered INITIAL event
        WorkflowExecutionCoordination reclaimedCoord = WorkflowExecutionCoordination.builder()
                .workflowId(10L).workflowName("Redelivered-Initial").generation(2L).instanceId("worker-2").build();
        when(coordinationService.tryAcquireLease(eq(10L), anyString(), eq("worker-2"), anyLong(), any(), eq(false), eq(1L)))
                .thenReturn(ExecutionLeaseAcquisitionResult.reclaimed(reclaimedCoord));

        WorkflowExecutionRequestEvent initialRedelivery = WorkflowExecutionRequestEvent.builder()
                .workflowId(10L).workflowName("Redelivered-Initial").generation(1L).resume(false).executionMode("INITIAL")
                .tasks(List.of(
                        TaskExecutionRequest.builder().taskKey("A").name("Root").taskType("NOOP").executionOrder(1).dependsOn(List.of()).build(),
                        TaskExecutionRequest.builder().taskKey("B").name("Child").taskType("TRANSFORM")
                                .config(Map.of("operation", "MERGE_UPSTREAM")).executionOrder(2).dependsOn(List.of("A")).build()
                )).build();

        consumer.consume(initialRedelivery, ack);

        ArgumentCaptor<WorkflowExecutionRequestEvent> dispatchedCap = ArgumentCaptor.forClass(WorkflowExecutionRequestEvent.class);
        verify(execService).executeWorkflow(dispatchedCap.capture(), eq(2L));
        WorkflowExecutionRequestEvent dispatched = dispatchedCap.getValue();
        assertThat(dispatched.getExecutionMode()).isEqualTo("RECOVERY");

        // Feed the exact converted event into DagExecutionCoordinator and verify A's checkpoint is preserved & hydrated
        TaskCheckpointService cpService = mock(TaskCheckpointService.class);
        WorkflowTaskCheckpoint cpA = WorkflowTaskCheckpoint.builder()
                .workflowId(10L).taskKey("A").status(TaskCheckpointStatus.COMPLETED)
                .executionGeneration(1L).outputJson("{\"persisted\":\"from-gen1\"}").updatedAt(Instant.now()).build();
        when(cpService.loadCheckpointsByTaskKey(10L)).thenReturn(Map.of("A", cpA));
        when(cpService.markTaskRunning(anyLong(), anyString(), anyString(), anyString(), any(), any(), anyLong(), anyString(), anyInt()))
                .thenReturn(true);

        DagExecutionCoordinator coord = newCoordinator(executor, registry, cpService);
        try {
            coord.executeDag(dispatched, 2L, "worker-2");
            verify(cpService, never()).resetCheckpointsForFreshRun(anyLong(), anyLong());
            ArgumentCaptor<TaskCompletedEvent> completedCap = ArgumentCaptor.forClass(TaskCompletedEvent.class);
            verify(producer, times(1)).publishTaskCompleted(completedCap.capture());
            assertThat(completedCap.getValue().getTaskKey()).isEqualTo("B");
            assertThat(completedCap.getValue().getOutputJson()).contains("from-gen1");
        } finally {
            coord.shutdownTimeoutScheduler();
        }
    }

    @Test
    void explicitUserRetryWithoutLeaseReclaimPreservesRetryModeAndResetsCheckpoints() {
        ExecutionService execService = mock(ExecutionService.class);
        WorkflowExecutionDispatcher dispatcher = mock(WorkflowExecutionDispatcher.class);
        when(dispatcher.getInstanceId()).thenReturn("worker-2");
        ExecutionRequestConsumer consumer = new ExecutionRequestConsumer(
                execService, coordinationService, dispatcher, new ExecutionWorkerProperties(), objectMapper, pauseRegistry, tracker
        );

        WorkflowExecutionCoordination retryCoord = WorkflowExecutionCoordination.builder()
                .workflowId(11L).workflowName("User-Retry").generation(2L).instanceId("worker-2").build();
        when(coordinationService.tryAcquireLease(eq(11L), anyString(), eq("worker-2"), anyLong(), any(), eq(false), eq(2L)))
                .thenReturn(ExecutionLeaseAcquisitionResult.acquired(retryCoord));

        WorkflowExecutionRequestEvent retryEvent = WorkflowExecutionRequestEvent.builder()
                .workflowId(11L).workflowName("User-Retry").generation(2L).resume(false).executionMode("RETRY")
                .tasks(List.of()).build();

        consumer.consume(retryEvent, mock(Acknowledgment.class));

        ArgumentCaptor<WorkflowExecutionRequestEvent> cap = ArgumentCaptor.forClass(WorkflowExecutionRequestEvent.class);
        verify(execService).executeWorkflow(cap.capture(), eq(2L));
        assertThat(cap.getValue().getExecutionMode()).isEqualTo("RETRY");
    }

    // =========================================================================
    // S28-R-M-02 & S28-R-m-02: Exception-Safe Timeout Settlement & Isolation
    // =========================================================================

    @Test
    void timeoutSettlementThrowingStaleWorkerExceptionFencesAndSettlesWithoutHanging() {
        CountDownLatch releaseHandler = new CountDownLatch(1);
        TaskCheckpointService cpService = mock(TaskCheckpointService.class);
        when(cpService.markTaskRunning(anyLong(), anyString(), anyString(), anyString(), any(), any(), anyLong(), anyString(), anyInt()))
                .thenReturn(true);
        doThrow(new StaleWorkerExecutionException("gen 1 fenced by gen 2"))
                .when(cpService).markTaskTerminal(anyLong(), anyString(), anyString(), anyString(), any(), any(), any(), anyLong(), anyString(), anyInt(), any(), any());

        DagExecutionCoordinator coord = newCoordinator(executor, blockingRegistry(releaseHandler), cpService);
        try {
            WorkflowExecutionRequestEvent event = singleTaskEvent(901L, "Timeout-Fenced-Checkpoint", 80L);
            // Must settle promptly without hanging even though handler is blocked and markTaskTerminal throws StaleWorkerExecutionException
            coord.executeDag(event, 1L, "w1");
            // Fenced worker must suppress WorkflowCompletedEvent
            verify(producer, never()).publishWorkflowCompleted(any());
        } finally {
            releaseHandler.countDown();
            coord.shutdownTimeoutScheduler();
        }
    }

    @Test
    void timeoutSettlementWhenIsFencedReturnsTrueSettlesWithoutHangingOrMutatingCheckpoints() {
        CountDownLatch releaseHandler = new CountDownLatch(1);
        AtomicBoolean fencedFlag = new AtomicBoolean(false);
        when(coordinationService.isFenced(eq(902L), eq(1L), eq("w1"))).thenAnswer(inv -> fencedFlag.get());

        TaskHandler fencedDuringRunHandler = new TaskHandler() {
            @Override
            public String getTaskType() {
                return "NOOP";
            }

            @Override
            public TaskHandlerResult execute(TaskHandlerContext context) {
                fencedFlag.set(true);
                awaitUninterruptibly(releaseHandler);
                return TaskHandlerResult.ok(Map.of("late", true));
            }
        };

        TaskCheckpointService cpService = mock(TaskCheckpointService.class);
        when(cpService.markTaskRunning(anyLong(), anyString(), anyString(), anyString(), any(), any(), anyLong(), anyString(), anyInt()))
                .thenReturn(true);

        DagExecutionCoordinator coord = newCoordinator(executor, new TaskHandlerRegistry(List.of(fencedDuringRunHandler)), cpService);
        try {
            coord.executeDag(singleTaskEvent(902L, "Timeout-Already-Fenced", 80L), 1L, "w1");
            verify(cpService, never()).markTaskTerminal(anyLong(), anyString(), anyString(), anyString(), any(), any(), any(), anyLong(), anyString(), anyInt(), any(), any());
            verify(producer, never()).publishWorkflowCompleted(any());
        } finally {
            releaseHandler.countDown();
            coord.shutdownTimeoutScheduler();
        }
    }

    @Test
    void timeoutSettlementHandlesCheckpointDbAndKafkaExceptionsWithoutHanging() {
        CountDownLatch releaseHandler = new CountDownLatch(1);
        TaskCheckpointService failingCp = mock(TaskCheckpointService.class);
        when(failingCp.markTaskRunning(anyLong(), anyString(), anyString(), anyString(), any(), any(), anyLong(), anyString(), anyInt()))
                .thenReturn(true);
        doThrow(new RuntimeException("PostgreSQL connection lost"))
                .when(failingCp).markTaskTerminal(anyLong(), anyString(), anyString(), anyString(), any(), any(), any(), anyLong(), anyString(), anyInt(), any(), any());

        DagExecutionCoordinator coord1 = newCoordinator(executor, blockingRegistry(releaseHandler), failingCp);
        try {
            coord1.executeDag(singleTaskEvent(903L, "Timeout-DB-Error", 80L), 1L, "w1");
            verify(producer).publishWorkflowCompleted(argThat(wf -> !wf.isSuccess()));
        } finally {
            releaseHandler.countDown();
            coord1.shutdownTimeoutScheduler();
        }

        // Now verify Kafka publishTaskCompleted throwing during timeout settlement also settles cleanly
        CountDownLatch releaseHandler2 = new CountDownLatch(1);
        ExecutionEventProducer failingProducer = mock(ExecutionEventProducer.class);
        doThrow(new RuntimeException("Kafka broker unavailable")).when(failingProducer).publishTaskCompleted(any());

        DagExecutionCoordinator coord2 = new DagExecutionCoordinator(
                executor, new DagExecutionProperties(), blockingRegistry(releaseHandler2), null, failingProducer,
                cancellationRegistry, pauseRegistry, coordinationService, tracker, objectMapper
        );
        try {
            coord2.executeDag(singleTaskEvent(904L, "Timeout-Kafka-Error", 80L), 1L, "w1");
            verify(failingProducer).publishWorkflowCompleted(argThat(wf -> !wf.isSuccess()));
        } finally {
            releaseHandler2.countDown();
            coord2.shutdownTimeoutScheduler();
        }
    }

    @Test
    void timeoutSettlementExecutorRejectionSettlesWorkflowFailedWithoutHanging() {
        CountDownLatch releaseHandler = new CountDownLatch(1);
        ExecutorService rejectingSettlement = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("settlement queue full"))
                .when(rejectingSettlement).execute(any(Runnable.class));

        DagExecutionCoordinator coord = newCoordinator(executor, blockingRegistry(releaseHandler), null);
        coord.setTimeoutSettlementExecutorForTest(rejectingSettlement);
        try {
            coord.executeDag(singleTaskEvent(905L, "Timeout-Settlement-Rejected", 80L), 1L, "w1");
            verify(producer).publishWorkflowCompleted(argThat(wf -> !wf.isSuccess()));
        } finally {
            releaseHandler.countDown();
            coord.shutdownTimeoutScheduler();
        }
    }

    @Test
    void timeoutSchedulerRemainsResponsiveWhenSettlementIoIsDelayed() throws Exception {
        CountDownLatch slowSettlementStarted = new CountDownLatch(1);
        CountDownLatch releaseSlowSettlement = new CountDownLatch(1);
        CountDownLatch secondTaskTimedOut = new CountDownLatch(1);
        CountDownLatch releaseHandlers = new CountDownLatch(1);

        TaskCheckpointService slowCp = mock(TaskCheckpointService.class);
        when(slowCp.markTaskRunning(anyLong(), anyString(), anyString(), anyString(), any(), any(), anyLong(), anyString(), anyInt()))
                .thenReturn(true);
        doAnswer(inv -> {
            String key = inv.getArgument(1);
            if ("T1".equals(key)) {
                slowSettlementStarted.countDown();
                releaseSlowSettlement.await(5, TimeUnit.SECONDS);
            } else if ("T2".equals(key)) {
                secondTaskTimedOut.countDown();
            }
            return null;
        }).when(slowCp).markTaskTerminal(anyLong(), anyString(), anyString(), anyString(), any(), any(), any(), anyLong(), anyString(), anyInt(), any(), any());

        DagExecutionCoordinator coord = newCoordinator(executor, blockingRegistry(releaseHandlers), slowCp);
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                    .workflowId(906L).workflowName("Concurrent-Timeout-Isolation").generation(1L).executionMode("INITIAL")
                    .tasks(List.of(
                            TaskExecutionRequest.builder().taskKey("T1").name("SlowSettle").taskType("NOOP")
                                    .timeoutMs(60L).maxRetries(0).executionOrder(1).dependsOn(List.of()).build(),
                            TaskExecutionRequest.builder().taskKey("T2").name("FastTimeout").taskType("NOOP")
                                    .timeoutMs(120L).maxRetries(0).executionOrder(2).dependsOn(List.of()).build()
                    )).build();

            Future<?> wfFuture = runner.submit(() -> coord.executeDag(event, 1L, "w1"));

            // T1 enters slow settlement I/O; T2 MUST still fire its timeout & settle while T1's I/O is blocked
            assertThat(slowSettlementStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(secondTaskTimedOut.await(3, TimeUnit.SECONDS)).isTrue();

            releaseSlowSettlement.countDown();
            wfFuture.get(5, TimeUnit.SECONDS);
            verify(producer).publishWorkflowCompleted(argThat(wf -> !wf.isSuccess()));
        } finally {
            releaseSlowSettlement.countDown();
            releaseHandlers.countDown();
            runner.shutdownNow();
            coord.shutdownTimeoutScheduler();
        }
    }

    private DagExecutionCoordinator newCoordinator(
            ThreadPoolTaskExecutor exec,
            TaskHandlerRegistry reg,
            TaskCheckpointService cpService
    ) {
        return new DagExecutionCoordinator(
                exec, new DagExecutionProperties(), reg, cpService, producer,
                cancellationRegistry, pauseRegistry, coordinationService, tracker, objectMapper
        );
    }

    private TaskHandlerRegistry blockingRegistry(CountDownLatch releaseLatch) {
        TaskHandler blocking = new TaskHandler() {
            @Override
            public String getTaskType() {
                return "NOOP";
            }

            @Override
            public TaskHandlerResult execute(TaskHandlerContext context) {
                awaitUninterruptibly(releaseLatch);
                return TaskHandlerResult.ok(Map.of("late", true));
            }
        };
        return new TaskHandlerRegistry(List.of(blocking));
    }

    private WorkflowExecutionRequestEvent singleTaskEvent(Long wfId, String name, long timeoutMs) {
        return WorkflowExecutionRequestEvent.builder()
                .workflowId(wfId).workflowName(name).generation(1L).executionMode("INITIAL")
                .tasks(List.of(
                        TaskExecutionRequest.builder().taskKey("T1").name("SlowTask").taskType("NOOP")
                                .timeoutMs(timeoutMs).maxRetries(0).executionOrder(1).dependsOn(List.of()).build()
                )).build();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        while (latch.getCount() > 0) {
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // Simulate non-interruptible handler
            }
        }
    }
}
