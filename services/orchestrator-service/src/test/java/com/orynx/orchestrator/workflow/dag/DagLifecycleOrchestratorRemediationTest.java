package com.orynx.orchestrator.workflow.dag;

import com.orynx.orchestrator.execution.ExecutionEventConsumer;
import com.orynx.orchestrator.execution.WorkflowExecution;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.execution.event.TaskCompletedEvent;
import com.orynx.orchestrator.execution.event.TaskStartedEvent;
import com.orynx.orchestrator.kafka.*;
import com.orynx.orchestrator.workflow.*;
import com.orynx.orchestrator.workflow.dto.CreateWorkflowTaskRequest;
import com.orynx.orchestrator.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowExecutionEngine;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DagLifecycleOrchestratorRemediationTest {

    private WorkflowRepository workflowRepository;
    private WorkflowTaskRepository workflowTaskRepository;
    private WorkflowExecutionRepository workflowExecutionRepository;
    private ExecutionRequestProducer executionRequestProducer;
    private WorkflowService workflowService;
    private ExecutionEventConsumer consumer;

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        workflowTaskRepository = mock(WorkflowTaskRepository.class);
        workflowExecutionRepository = mock(WorkflowExecutionRepository.class);
        executionRequestProducer = mock(ExecutionRequestProducer.class);

        workflowService = new WorkflowService(
                workflowRepository,
                mock(KafkaProducer.class),
                mock(WorkflowEventPublisher.class),
                workflowTaskRepository,
                mock(WorkflowExecutionEngine.class),
                executionRequestProducer,
                workflowExecutionRepository,
                mock(WorkflowCancellationProducer.class),
                mock(WorkflowPauseProducer.class),
                mock(WorkflowResumeProducer.class)
        );
        consumer = new ExecutionEventConsumer(
                workflowRepository,
                workflowTaskRepository,
                workflowExecutionRepository,
                mock(WorkflowEventPublisher.class)
        );
    }

    @Test
    void dagResumeAfterOutOfOrderSiblingCompletionPublishesFullDagAndDoesNotDropIncompleteSibling() {
        Workflow wf = Workflow.builder().id(10L).name("Diamond-DAG").status(WorkflowStatus.PAUSED).userId(1L).build();
        WorkflowExecution exec = WorkflowExecution.builder()
                .id(50L).workflow(wf).status(WorkflowStatus.PAUSED)
                .executionGeneration(1L)
                // Even if scalar watermark jumped to 3, DAG resume must rely on task states, NOT executionOrder
                .lastCompletedExecutionOrder(3)
                .build();

        WorkflowTask tA = WorkflowTask.builder().id(1L).taskKey("A").name("Root").executionOrder(1).dependsOnJson("[]").status(TaskStatus.COMPLETED).workflow(wf).build();
        WorkflowTask tB = WorkflowTask.builder().id(2L).taskKey("B").name("Left").executionOrder(2).dependsOnJson("[\"A\"]").status(TaskStatus.PENDING).workflow(wf).build();
        WorkflowTask tC = WorkflowTask.builder().id(3L).taskKey("C").name("Right").executionOrder(3).dependsOnJson("[\"A\"]").status(TaskStatus.COMPLETED).workflow(wf).build();
        WorkflowTask tD = WorkflowTask.builder().id(4L).taskKey("D").name("Join").executionOrder(4).dependsOnJson("[\"B\",\"C\"]").status(TaskStatus.PENDING).workflow(wf).build();

        when(workflowRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(wf));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(10L)).thenReturn(Optional.of(exec));
        when(workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(10L)).thenReturn(List.of(tA, tB, tC, tD));

        workflowService.resumeWorkflow(10L, 1L);

        ArgumentCaptor<WorkflowExecutionRequestEvent> reqCap = ArgumentCaptor.forClass(WorkflowExecutionRequestEvent.class);
        verify(executionRequestProducer).publishExecutionRequest(reqCap.capture());

        WorkflowExecutionRequestEvent sent = reqCap.getValue();
        assertThat(sent.getExecutionMode()).isEqualTo("RESUME");
        assertThat(sent.getResume()).isTrue();
        assertThat(sent.getGeneration()).isEqualTo(2L);
        assertThat(sent.getTasks()).hasSize(4);
        assertThat(sent.getTasks()).extracting("taskKey").containsExactly("A", "B", "C", "D");
        assertThat(sent.getCompletedTaskKeys()).containsExactlyInAnyOrder("A", "C");
    }

    @Test
    void startAndRetryWorkflowPublishExplicitInitialAndRetryExecutionModes() {
        // 1. startWorkflow -> INITIAL
        Workflow createdWf = Workflow.builder().id(20L).name("Start-WF").status(WorkflowStatus.CREATED).userId(1L).build();
        WorkflowTask t1 = WorkflowTask.builder().id(1L).taskKey("A").name("Root").executionOrder(1).dependsOnJson("[]").status(TaskStatus.PENDING).workflow(createdWf).build();
        when(workflowRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(createdWf));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(20L)).thenReturn(List.of(t1));

        workflowService.startWorkflow(20L, 1L);

        ArgumentCaptor<WorkflowExecutionRequestEvent> startCap = ArgumentCaptor.forClass(WorkflowExecutionRequestEvent.class);
        verify(executionRequestProducer).publishExecutionRequest(startCap.capture());
        assertThat(startCap.getValue().getExecutionMode()).isEqualTo("INITIAL");
        assertThat(startCap.getValue().getResume()).isFalse();

        // 2. retryWorkflow -> RETRY with all tasks reset to PENDING
        reset(executionRequestProducer);
        Workflow failedWf = Workflow.builder().id(21L).name("Retry-WF").status(WorkflowStatus.FAILED).userId(1L).build();
        WorkflowTask rA = WorkflowTask.builder().id(11L).taskKey("A").name("Root").executionOrder(1).dependsOnJson("[]").status(TaskStatus.COMPLETED).outputJson("{\"x\":1}").workflow(failedWf).build();
        WorkflowTask rB = WorkflowTask.builder().id(12L).taskKey("B").name("Child").executionOrder(2).dependsOnJson("[\"A\"]").status(TaskStatus.FAILED).errorMessage("boom").workflow(failedWf).build();
        WorkflowExecution prevExec = WorkflowExecution.builder().id(60L).workflow(failedWf).status(WorkflowStatus.FAILED).executionGeneration(1L).build();

        when(workflowRepository.findByIdAndUserId(21L, 1L)).thenReturn(Optional.of(failedWf));
        when(workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(21L)).thenReturn(List.of(rA, rB));
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(21L)).thenReturn(Optional.of(prevExec));

        workflowService.retryWorkflow(21L, 1L);

        assertThat(rA.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(rA.getOutputJson()).isNull();
        assertThat(rB.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(rB.getErrorMessage()).isNull();

        ArgumentCaptor<WorkflowExecutionRequestEvent> retryCap = ArgumentCaptor.forClass(WorkflowExecutionRequestEvent.class);
        verify(executionRequestProducer).publishExecutionRequest(retryCap.capture());
        assertThat(retryCap.getValue().getExecutionMode()).isEqualTo("RETRY");
        assertThat(retryCap.getValue().getResume()).isFalse();
        assertThat(retryCap.getValue().getGeneration()).isEqualTo(2L);
    }

    @Test
    void outOfOrderParallelSiblingCompletionDoesNotAdvanceWatermarkPastIncompleteLowerOrderTask() {
        Workflow wf = Workflow.builder().id(30L).name("Watermark-DAG").status(WorkflowStatus.RUNNING).build();
        WorkflowExecution exec = WorkflowExecution.builder()
                .id(70L).workflow(wf).status(WorkflowStatus.RUNNING)
                .executionGeneration(1L).lastCompletedExecutionOrder(1).build();

        WorkflowTask tA = WorkflowTask.builder().id(1L).taskKey("A").name("Root").executionOrder(1).dependsOnJson("[]").status(TaskStatus.COMPLETED).workflow(wf).build();
        WorkflowTask tB = WorkflowTask.builder().id(2L).taskKey("B").name("Left").executionOrder(2).dependsOnJson("[\"A\"]").status(TaskStatus.RUNNING).workflow(wf).build();
        WorkflowTask tC = WorkflowTask.builder().id(3L).taskKey("C").name("Right").executionOrder(3).dependsOnJson("[\"A\"]").status(TaskStatus.RUNNING).workflow(wf).build();
        WorkflowTask tD = WorkflowTask.builder().id(4L).taskKey("D").name("Join").executionOrder(4).dependsOnJson("[\"B\",\"C\"]").status(TaskStatus.PENDING).workflow(wf).build();

        when(workflowRepository.findById(30L)).thenReturn(Optional.of(wf));
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(30L)).thenReturn(Optional.of(exec));
        when(workflowTaskRepository.findByWorkflowIdAndTaskKey(30L, "C")).thenReturn(Optional.of(tC));
        when(workflowTaskRepository.findByWorkflowIdAndTaskKey(30L, "B")).thenReturn(Optional.of(tB));
        when(workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(30L)).thenReturn(List.of(tA, tB, tC, tD));

        // C (order 3) finishes before B (order 2) -> watermark must stay at 1
        consumer.consumeTaskCompleted(TaskCompletedEvent.builder()
                .workflowId(30L).workflowName("Watermark-DAG").taskKey("C").taskName("Right")
                .executionOrder(3).success(true).generation(1L).build());

        assertThat(tC.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(exec.getLastCompletedExecutionOrder()).isEqualTo(1);

        // Now B (order 2) finishes -> contiguous completed prefix advances to 3
        consumer.consumeTaskCompleted(TaskCompletedEvent.builder()
                .workflowId(30L).workflowName("Watermark-DAG").taskKey("B").taskName("Left")
                .executionOrder(2).success(true).generation(1L).build());

        assertThat(tB.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(exec.getLastCompletedExecutionOrder()).isEqualTo(3);
    }

    @Test
    void lateTaskStartedEventCannotRegressSkippedOrCancelledTask() {
        Workflow wf = Workflow.builder().id(31L).name("Terminal-Guard").status(WorkflowStatus.RUNNING).build();
        WorkflowTask skippedTask = WorkflowTask.builder()
                .id(5L).taskKey("D").name("Join").executionOrder(4).status(TaskStatus.SKIPPED).workflow(wf).build();
        when(workflowTaskRepository.findByWorkflowIdAndTaskKey(31L, "D")).thenReturn(Optional.of(skippedTask));
        when(workflowRepository.findById(31L)).thenReturn(Optional.of(wf));

        consumer.consumeTaskStarted(TaskStartedEvent.builder()
                .workflowId(31L).workflowName("Terminal-Guard").taskKey("D").taskName("Join")
                .executionOrder(4).generation(1L).build());

        assertThat(skippedTask.getStatus()).isEqualTo(TaskStatus.SKIPPED);
        verify(workflowTaskRepository, never()).save(any());
    }

    @Test
    void dagValidatorRejectsOversizedConfigAndExcessiveNestingDepth() {
        DagValidator validator = new DagValidator();

        // Oversized config (>16KB)
        assertThatThrownBy(() -> validator.validateAndNormalize(List.of(
                CreateWorkflowTaskRequest.builder()
                        .taskKey("A").name("BigCfg").taskType("ECHO").dependsOn(List.of())
                        .config(Map.of("payload", "z".repeat(DagValidator.MAX_CONFIG_BYTES + 100)))
                        .build()
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maximum serialized size");

        // Excessive nesting depth (>8)
        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cursor = deep;
        for (int i = 0; i < DagValidator.MAX_CONFIG_NESTING_DEPTH + 2; i++) {
            Map<String, Object> next = new LinkedHashMap<>();
            cursor.put("lvl" + i, next);
            cursor = next;
        }
        assertThatThrownBy(() -> validator.validateAndNormalize(List.of(
                CreateWorkflowTaskRequest.builder()
                        .taskKey("A").name("DeepCfg").taskType("ECHO").dependsOn(List.of())
                        .config(deep)
                        .build()
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maximum nesting depth");
    }
}
