package com.orynx.orchestrator.execution;

import com.orynx.orchestrator.event.dto.WorkflowExecutionPausedEvent;
import com.orynx.orchestrator.execution.event.TaskCompletedEvent;
import com.orynx.orchestrator.execution.event.WorkflowCompletedEvent;
import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowRepository;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionLifecycleReliabilityTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowTaskRepository workflowTaskRepository;

    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;

    private ExecutionEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ExecutionEventConsumer(workflowRepository, workflowTaskRepository, workflowExecutionRepository);
    }

    @Test
    @DisplayName("consumeWorkflowCompleted must not overwrite terminal CANCELLED workflow status")
    void shouldNotOverwriteCancelledWorkflowStatusOnLateCompletionEvent() {
        Workflow cancelledWorkflow = Workflow.builder()
                .id(101L)
                .name("Cancelled-Pipeline")
                .status(WorkflowStatus.CANCELLED)
                .build();

        when(workflowRepository.findById(101L)).thenReturn(Optional.of(cancelledWorkflow));

        WorkflowCompletedEvent event = WorkflowCompletedEvent.builder()
                .workflowId(101L)
                .workflowName("Cancelled-Pipeline")
                .success(true)
                .build();

        consumer.consumeWorkflowCompleted(event);

        // Verify workflow status was never changed or saved
        assertThat(cancelledWorkflow.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
        verify(workflowRepository, never()).save(any());
        verify(workflowExecutionRepository, never()).save(any());
    }

    @Test
    @DisplayName("consumeWorkflowCompleted must preserve terminal CANCELLED status on WorkflowExecution")
    void shouldNotOverwriteCancelledExecutionStatusOnLateCompletionEvent() {
        Workflow runningWorkflow = Workflow.builder()
                .id(102L)
                .name("Running-Workflow")
                .status(WorkflowStatus.RUNNING)
                .build();

        WorkflowExecution cancelledExecution = WorkflowExecution.builder()
                .id(502L)
                .workflow(runningWorkflow)
                .status(WorkflowStatus.CANCELLED)
                .startedAt(System.currentTimeMillis() - 5000)
                .build();

        when(workflowRepository.findById(102L)).thenReturn(Optional.of(runningWorkflow));
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(102L))
                .thenReturn(Optional.of(cancelledExecution));

        WorkflowCompletedEvent event = WorkflowCompletedEvent.builder()
                .workflowId(102L)
                .workflowName("Running-Workflow")
                .success(true)
                .build();

        consumer.consumeWorkflowCompleted(event);

        // Workflow updated to COMPLETED, but cancelled execution is preserved as CANCELLED
        verify(workflowRepository).save(runningWorkflow);
        assertThat(cancelledExecution.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
        verify(workflowExecutionRepository, never()).save(cancelledExecution);
    }

    @Test
    @DisplayName("consumeWorkflowExecutionPaused must not overwrite terminal CANCELLED status on WorkflowExecution")
    void shouldNotOverwriteCancelledExecutionStatusOnLatePauseEvent() {
        WorkflowExecution cancelledExecution = WorkflowExecution.builder()
                .id(503L)
                .status(WorkflowStatus.CANCELLED)
                .lastCompletedExecutionOrder(2)
                .build();

        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(103L))
                .thenReturn(Optional.of(cancelledExecution));

        WorkflowExecutionPausedEvent event = WorkflowExecutionPausedEvent.builder()
                .workflowId(103L)
                .workflowName("Cancelled-Pipeline")
                .lastCompletedExecutionOrder(2)
                .build();

        consumer.consumeWorkflowExecutionPaused(event);

        assertThat(cancelledExecution.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
        verify(workflowExecutionRepository, never()).save(any());
    }

    @Test
    @DisplayName("consumeWorkflowExecutionPaused must not overwrite valid checkpoint with null")
    void shouldNotOverwriteValidCheckpointWithNullOnPause() {
        WorkflowExecution execution = WorkflowExecution.builder()
                .id(504L)
                .status(WorkflowStatus.RUNNING)
                .lastCompletedExecutionOrder(3)
                .build();

        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(104L))
                .thenReturn(Optional.of(execution));

        // When a resumed workflow pauses before completing any new task, lastCompletedExecutionOrder is null
        WorkflowExecutionPausedEvent event = WorkflowExecutionPausedEvent.builder()
                .workflowId(104L)
                .workflowName("Resumed-Pipeline")
                .lastCompletedExecutionOrder(null)
                .build();

        consumer.consumeWorkflowExecutionPaused(event);

        assertThat(execution.getStatus()).isEqualTo(WorkflowStatus.PAUSED);
        // Checkpoint must NOT be overwritten with null
        assertThat(execution.getLastCompletedExecutionOrder()).isEqualTo(3);
        verify(workflowExecutionRepository).save(execution);
    }

    @Test
    @DisplayName("consumeWorkflowExecutionPaused must advance checkpoints monotonically and reject regressions")
    void shouldNotRegressCheckpointOrderOnPause() {
        WorkflowExecution execution = WorkflowExecution.builder()
                .id(505L)
                .status(WorkflowStatus.RUNNING)
                .lastCompletedExecutionOrder(4)
                .build();

        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(105L))
                .thenReturn(Optional.of(execution));

        // Stale pause event with order 2 should not regress checkpoint from 4
        WorkflowExecutionPausedEvent staleEvent = WorkflowExecutionPausedEvent.builder()
                .workflowId(105L)
                .workflowName("Monotonic-Pipeline")
                .lastCompletedExecutionOrder(2)
                .build();

        consumer.consumeWorkflowExecutionPaused(staleEvent);

        assertThat(execution.getLastCompletedExecutionOrder()).isEqualTo(4);
        verify(workflowExecutionRepository).save(execution);
    }

    @Test
    @DisplayName("consumeTaskCompleted must advance checkpoint monotonically and ignore cancelled execution")
    void shouldAdvanceCheckpointMonotonicallyAndIgnoreCancelledExecution() {
        WorkflowTask task = WorkflowTask.builder()
                .name("Task 2")
                .executionOrder(2)
                .status(TaskStatus.RUNNING)
                .build();

        when(workflowTaskRepository.findByWorkflowIdAndExecutionOrder(106L, 2))
                .thenReturn(Optional.of(task));

        WorkflowExecution cancelledExecution = WorkflowExecution.builder()
                .id(506L)
                .status(WorkflowStatus.CANCELLED)
                .lastCompletedExecutionOrder(1)
                .build();

        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(106L))
                .thenReturn(Optional.of(cancelledExecution));

        TaskCompletedEvent event = TaskCompletedEvent.builder()
                .workflowId(106L)
                .workflowName("Pipeline")
                .taskName("Task 2")
                .executionOrder(2)
                .success(true)
                .build();

        consumer.consumeTaskCompleted(event);

        // Task marked completed, but cancelled execution is untouched
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        verify(workflowTaskRepository).save(task);
        assertThat(cancelledExecution.getLastCompletedExecutionOrder()).isEqualTo(1);
        verify(workflowExecutionRepository, never()).save(cancelledExecution);
    }
}
