package com.orynx.execution.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.execution.checkpoint.TaskCheckpointService;
import com.orynx.execution.checkpoint.TaskCheckpointStatus;
import com.orynx.execution.checkpoint.WorkflowTaskCheckpoint;
import com.orynx.execution.checkpoint.WorkflowTaskCheckpointRepository;
import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.coordination.StaleWorkerExecutionException;
import com.orynx.execution.event.dto.TaskCompletedEvent;
import com.orynx.execution.event.dto.WorkflowCompletedEvent;
import com.orynx.execution.execution.ActiveExecutionTracker;
import com.orynx.execution.execution.WorkflowCancellationRegistry;
import com.orynx.execution.execution.WorkflowPauseRegistry;
import com.orynx.execution.handler.*;
import com.orynx.execution.kafka.ExecutionEventProducer;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.execution.workflow.event.dto.TaskExecutionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DagExecutionCoordinatorTest {

    private ThreadPoolTaskExecutor executor;
    private ExecutionEventProducer producer;
    private WorkflowCancellationRegistry cancellationRegistry;
    private WorkflowPauseRegistry pauseRegistry;
    private ExecutionCoordinationService coordinationService;
    private ActiveExecutionTracker tracker;
    private TaskHandlerRegistry registry;
    private DagExecutionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(6);
        executor.setMaxPoolSize(12);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("test-dag-");
        executor.initialize();

        producer = mock(ExecutionEventProducer.class);
        cancellationRegistry = new WorkflowCancellationRegistry();
        pauseRegistry = new WorkflowPauseRegistry();
        coordinationService = mock(ExecutionCoordinationService.class);
        when(coordinationService.isFenced(anyLong(), anyLong(), anyString())).thenReturn(false);
        tracker = new ActiveExecutionTracker();

        registry = new TaskHandlerRegistry(List.of(
                new NoopTaskHandler(),
                new EchoTaskHandler(),
                new TransformTaskHandler(),
                new SimulatedTaskHandler()
        ));

        DagExecutionProperties props = new DagExecutionProperties();
        props.setMaxPerWorkflowConcurrency(4);

        coordinator = new DagExecutionCoordinator(
                executor, props, registry, null, producer,
                cancellationRegistry, pauseRegistry, coordinationService, tracker, new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        coordinator.shutdownTimeoutScheduler();
        executor.shutdown();
    }

    @Test
    void diamondDagExecutesBranchesInParallelAndRunsJoinExactlyOnceWithUpstreamOutputs() {
        AtomicInteger concurrentBranches = new AtomicInteger(0);
        AtomicInteger maxConcurrentObserved = new AtomicInteger(0);
        CountDownLatch bothBranchesRunning = new CountDownLatch(2);
        AtomicInteger joinExecutions = new AtomicInteger(0);

        TaskHandler barrierHandler = new TaskHandler() {
            @Override
            public String getTaskType() {
                return "NOOP";
            }

            @Override
            public TaskHandlerResult execute(TaskHandlerContext ctx) throws Exception {
                if ("B".equals(ctx.getTaskKey()) || "C".equals(ctx.getTaskKey())) {
                    int now = concurrentBranches.incrementAndGet();
                    maxConcurrentObserved.accumulateAndGet(now, Math::max);
                    bothBranchesRunning.countDown();
                    boolean overlapped = bothBranchesRunning.await(5, TimeUnit.SECONDS);
                    concurrentBranches.decrementAndGet();
                    return TaskHandlerResult.ok(Map.of("branch", ctx.getTaskKey(), "overlapped", overlapped));
                }
                if ("D".equals(ctx.getTaskKey())) {
                    joinExecutions.incrementAndGet();
                    return TaskHandlerResult.ok(Map.of("joined", ctx.getUpstreamOutputs().keySet().stream().sorted().toList()));
                }
                return TaskHandlerResult.ok(Map.of("root", true));
            }
        };

        TaskHandlerRegistry customRegistry = new TaskHandlerRegistry(List.of(barrierHandler, new TransformTaskHandler()));
        DagExecutionProperties props = new DagExecutionProperties();
        props.setMaxPerWorkflowConcurrency(4);
        DagExecutionCoordinator customCoord = new DagExecutionCoordinator(
                executor, props, customRegistry, null, producer,
                cancellationRegistry, pauseRegistry, coordinationService, tracker, new ObjectMapper()
        );

        try {
            WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                    .workflowId(100L)
                    .workflowName("Diamond-DAG")
                    .generation(1L)
                    .tasks(List.of(
                            TaskExecutionRequest.builder().taskKey("A").name("Root").taskType("NOOP").executionOrder(1).dependsOn(List.of()).build(),
                            TaskExecutionRequest.builder().taskKey("B").name("Left").taskType("NOOP").executionOrder(2).dependsOn(List.of("A")).build(),
                            TaskExecutionRequest.builder().taskKey("C").name("Right").taskType("NOOP").executionOrder(3).dependsOn(List.of("A")).build(),
                            TaskExecutionRequest.builder().taskKey("D").name("Join").taskType("NOOP").executionOrder(4).dependsOn(List.of("B", "C")).build()
                    ))
                    .build();

            customCoord.executeDag(event, 1L, "worker-1");

            assertThat(maxConcurrentObserved.get()).isEqualTo(2);
            assertThat(joinExecutions.get()).isEqualTo(1);

            ArgumentCaptor<WorkflowCompletedEvent> wfCap = ArgumentCaptor.forClass(WorkflowCompletedEvent.class);
            verify(producer).publishWorkflowCompleted(wfCap.capture());
            assertThat(wfCap.getValue().isSuccess()).isTrue();
        } finally {
            customCoord.shutdownTimeoutScheduler();
        }
    }

    @Test
    void failureInBranchSkipsDownstreamJoinAndMarksWorkflowFailed() {
        WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                .workflowId(101L)
                .workflowName("Fail-Prop-DAG")
                .generation(1L)
                .tasks(List.of(
                        TaskExecutionRequest.builder().taskKey("A").name("Root").taskType("NOOP").executionOrder(1).dependsOn(List.of()).build(),
                        TaskExecutionRequest.builder().taskKey("B").name("FailingBranch").taskType("SIMULATED")
                                .config(Map.of("forceFail", true)).maxRetries(1).executionOrder(2).dependsOn(List.of("A")).build(),
                        TaskExecutionRequest.builder().taskKey("C").name("HealthyBranch").taskType("NOOP").executionOrder(3).dependsOn(List.of("A")).build(),
                        TaskExecutionRequest.builder().taskKey("D").name("Join").taskType("NOOP").executionOrder(4).dependsOn(List.of("B", "C")).build()
                ))
                .build();

        coordinator.executeDag(event, 1L, "worker-1");

        ArgumentCaptor<TaskCompletedEvent> taskCap = ArgumentCaptor.forClass(TaskCompletedEvent.class);
        verify(producer, atLeast(4)).publishTaskCompleted(taskCap.capture());

        Map<String, TaskCompletedEvent> byKey = new HashMap<>();
        for (TaskCompletedEvent ev : taskCap.getAllValues()) {
            byKey.put(ev.getTaskKey(), ev);
        }
        assertThat(byKey.get("A").isSuccess()).isTrue();
        assertThat(byKey.get("B").isSuccess()).isFalse();
        assertThat(byKey.get("B").getAttempts()).isEqualTo(2);
        assertThat(byKey.get("D").isSkipped()).isTrue();

        ArgumentCaptor<WorkflowCompletedEvent> wfCap = ArgumentCaptor.forClass(WorkflowCompletedEvent.class);
        verify(producer).publishWorkflowCompleted(wfCap.capture());
        assertThat(wfCap.getValue().isSuccess()).isFalse();
    }

    @Test
    void taskTimeoutInterruptsHungTaskCleanlyAndFailsWorkflow() {
        WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                .workflowId(102L)
                .workflowName("Timeout-DAG")
                .generation(1L)
                .tasks(List.of(
                        TaskExecutionRequest.builder().taskKey("T1").name("SlowTask").taskType("SIMULATED")
                                .config(Map.of("delayMs", 3000)).timeoutMs(150L).maxRetries(0).executionOrder(1).dependsOn(List.of()).build()
                ))
                .build();

        coordinator.executeDag(event, 1L, "worker-1");

        ArgumentCaptor<TaskCompletedEvent> taskCap = ArgumentCaptor.forClass(TaskCompletedEvent.class);
        verify(producer).publishTaskCompleted(taskCap.capture());
        assertThat(taskCap.getValue().isSuccess()).isFalse();
        assertThat(taskCap.getValue().getErrorMessage()).contains("timed out");
    }

    @Test
    void recoveryFromDurableCheckpointsSkipsCompletedTasksAndResumesFrontier() {
        TaskCheckpointService cpService = mock(TaskCheckpointService.class);
        WorkflowTaskCheckpoint cpA = WorkflowTaskCheckpoint.builder()
                .workflowId(103L).taskKey("A").status(TaskCheckpointStatus.COMPLETED)
                .executionGeneration(1L).outputJson("{\"fromA\":\"hello\"}").updatedAt(Instant.now()).build();
        WorkflowTaskCheckpoint cpB = WorkflowTaskCheckpoint.builder()
                .workflowId(103L).taskKey("B").status(TaskCheckpointStatus.COMPLETED)
                .executionGeneration(1L).outputJson("{\"fromB\":\"world\"}").updatedAt(Instant.now()).build();

        when(cpService.loadCheckpointsByTaskKey(103L)).thenReturn(Map.of("A", cpA, "B", cpB));
        when(cpService.markTaskRunning(anyLong(), anyString(), anyString(), anyString(), any(), any(), anyLong(), anyString(), anyInt()))
                .thenReturn(true);

        DagExecutionProperties props = new DagExecutionProperties();
        props.setMaxPerWorkflowConcurrency(4);
        DagExecutionCoordinator recCoord = new DagExecutionCoordinator(
                executor, props, registry, cpService, producer,
                cancellationRegistry, pauseRegistry, coordinationService, tracker, new ObjectMapper()
        );

        try {
            WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                    .workflowId(103L)
                    .workflowName("Recovery-DAG")
                    .generation(2L)
                    .resume(true)
                    .tasks(List.of(
                            TaskExecutionRequest.builder().taskKey("A").name("Root").taskType("NOOP").executionOrder(1).dependsOn(List.of()).build(),
                            TaskExecutionRequest.builder().taskKey("B").name("Left").taskType("NOOP").executionOrder(2).dependsOn(List.of("A")).build(),
                            TaskExecutionRequest.builder().taskKey("C").name("Right").taskType("ECHO").config(Map.of("fromC", "recovered")).executionOrder(3).dependsOn(List.of("A")).build(),
                            TaskExecutionRequest.builder().taskKey("D").name("Join").taskType("TRANSFORM").config(Map.of("operation", "MERGE_UPSTREAM")).executionOrder(4).dependsOn(List.of("B", "C")).build()
                    ))
                    .build();

            recCoord.executeDag(event, 2L, "worker-2");

            ArgumentCaptor<TaskCompletedEvent> taskCap = ArgumentCaptor.forClass(TaskCompletedEvent.class);
            verify(producer, times(2)).publishTaskCompleted(taskCap.capture());
            List<String> executedKeys = taskCap.getAllValues().stream().map(TaskCompletedEvent::getTaskKey).toList();
            assertThat(executedKeys).containsExactly("C", "D");
            // D received persisted output from B (recovered from checkpoint) AND fresh output from C
            TaskCompletedEvent joinEvent = taskCap.getAllValues().get(1);
            assertThat(joinEvent.getOutputJson()).contains("world").contains("recovered");
        } finally {
            recCoord.shutdownTimeoutScheduler();
        }
    }

    @Test
    void checkpointServiceFencesStaleGenerationAndProtectsCompletedTasks() {
        WorkflowTaskCheckpointRepository repo = mock(WorkflowTaskCheckpointRepository.class);
        TaskCheckpointService cpService = new TaskCheckpointService(repo);

        WorkflowTaskCheckpoint activeGen2 = WorkflowTaskCheckpoint.builder()
                .workflowId(200L).taskKey("A").status(TaskCheckpointStatus.RUNNING)
                .executionGeneration(2L).updatedAt(Instant.now()).build();
        when(repo.findByWorkflowIdAndTaskKeyForUpdate(200L, "A")).thenReturn(Optional.of(activeGen2));

        // Stale gen 1 must throw StaleWorkerExecutionException
        assertThatThrownBy(() -> cpService.markTaskTerminal(
                200L, "A", "Root", "NOOP", 1, "[]",
                TaskCheckpointStatus.FAILED, 1L, "old-worker", 1, null, "stale"
        )).isInstanceOf(StaleWorkerExecutionException.class);

        // Completed task cannot be regressed to FAILED
        WorkflowTaskCheckpoint completed = WorkflowTaskCheckpoint.builder()
                .workflowId(200L).taskKey("B").status(TaskCheckpointStatus.COMPLETED)
                .executionGeneration(2L).outputJson("{\"ok\":true}").updatedAt(Instant.now()).build();
        when(repo.findByWorkflowIdAndTaskKeyForUpdate(200L, "B")).thenReturn(Optional.of(completed));

        cpService.markTaskTerminal(200L, "B", "B", "NOOP", 2, "[]",
                TaskCheckpointStatus.FAILED, 2L, "worker-2", 1, null, "late failure");
        assertThat(completed.getStatus()).isEqualTo(TaskCheckpointStatus.COMPLETED);
    }

    @Test
    void boundedPerWorkflowConcurrencyNeverExceedsConfiguredLimit() {
        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);

        TaskHandler slowHandler = new TaskHandler() {
            @Override
            public String getTaskType() {
                return "NOOP";
            }

            @Override
            public TaskHandlerResult execute(TaskHandlerContext ctx) throws Exception {
                int c = active.incrementAndGet();
                maxObserved.accumulateAndGet(c, Math::max);
                Thread.sleep(60);
                active.decrementAndGet();
                return TaskHandlerResult.ok(Map.of("k", ctx.getTaskKey()));
            }
        };

        DagExecutionProperties props = new DagExecutionProperties();
        props.setMaxPerWorkflowConcurrency(3);
        DagExecutionCoordinator boundedCoord = new DagExecutionCoordinator(
                executor, props, new TaskHandlerRegistry(List.of(slowHandler)), null, producer,
                cancellationRegistry, pauseRegistry, coordinationService, tracker, new ObjectMapper()
        );

        try {
            List<TaskExecutionRequest> wide = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                wide.add(TaskExecutionRequest.builder()
                        .taskKey("W" + i).name("Wide-" + i).taskType("NOOP")
                        .executionOrder(i).dependsOn(List.of()).build());
            }
            WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                    .workflowId(104L).workflowName("Wide-Bounded-DAG").generation(1L).tasks(wide).build();

            boundedCoord.executeDag(event, 1L, "worker-1");

            assertThat(maxObserved.get()).isLessThanOrEqualTo(3);
            assertThat(maxObserved.get()).isGreaterThanOrEqualTo(2);
            verify(producer, times(10)).publishTaskCompleted(any());
        } finally {
            boundedCoord.shutdownTimeoutScheduler();
        }
    }

    @Test
    void transformAndSimulatedHandlersExecuteDeterministically() throws Exception {
        TransformTaskHandler transform = new TransformTaskHandler();
        TaskHandlerResult upper = transform.execute(TaskHandlerContext.builder()
                .taskKey("T").config(Map.of("operation", "UPPERCASE", "value", "orynx")).build());
        assertThat(upper.isSuccess()).isTrue();
        assertThat(upper.getOutput()).containsEntry("result", "ORYNX");

        SimulatedTaskHandler sim = new SimulatedTaskHandler();
        TaskHandlerResult fail1 = sim.execute(TaskHandlerContext.builder()
                .taskKey("S").attempt(1).config(Map.of("failUntilAttempt", 1)).build());
        TaskHandlerResult ok2 = sim.execute(TaskHandlerContext.builder()
                .taskKey("S").attempt(2).config(Map.of("failUntilAttempt", 1)).build());
        assertThat(fail1.isSuccess()).isFalse();
        assertThat(ok2.isSuccess()).isTrue();
    }
}
