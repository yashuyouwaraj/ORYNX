package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.event.dto.WorkflowCancelledEvent;
import com.orynx.orchestrator.event.dto.WorkflowPausedEvent;
import com.orynx.orchestrator.event.dto.WorkflowResumedEvent;
import com.orynx.orchestrator.execution.ExecutionEventConsumer;
import com.orynx.orchestrator.execution.WorkflowExecution;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.kafka.*;
import com.orynx.orchestrator.workflow.dto.CreateWorkflowRequest;
import com.orynx.orchestrator.execution.event.TaskCompletedEvent;
import com.orynx.orchestrator.execution.event.TaskStartedEvent;
import com.orynx.orchestrator.execution.event.WorkflowCompletedEvent;
import com.orynx.orchestrator.workflow.event.WorkflowExecutionEvent;
import com.orynx.orchestrator.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowExecutionEngine;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowLifecycleTransitionTest {

    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private KafkaProducer kafkaProducer;
    @Mock
    private WorkflowEventPublisher workflowEventPublisher;
    @Mock
    private WorkflowTaskRepository workflowTaskRepository;
    @Mock
    private WorkflowExecutionEngine workflowExecutionEngine;
    @Mock
    private ExecutionRequestProducer executionRequestProducer;
    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock
    private WorkflowCancellationProducer workflowCancellationProducer;
    @Mock
    private WorkflowPauseProducer workflowPauseProducer;
    @Mock
    private WorkflowResumeProducer workflowResumeProducer;

    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        workflowService = new WorkflowService(
                workflowRepository,
                kafkaProducer,
                workflowEventPublisher,
                workflowTaskRepository,
                workflowExecutionEngine,
                executionRequestProducer,
                workflowExecutionRepository,
                workflowCancellationProducer,
                workflowPauseProducer,
                workflowResumeProducer
        );
    }

    private Workflow createMockWorkflow(Long id, WorkflowStatus status) {
        return Workflow.builder()
                .id(id)
                .name("Test Workflow " + id)
                .goal("Goal for " + id)
                .status(status)
                .userId(100L)
                .scheduled(false)
                .build();
    }

    // ==========================================
    // 1. START WORKFLOW TRANSITIONS
    // ==========================================
    @Nested
    @DisplayName("startWorkflow State Transitions")
    class StartWorkflowTransitions {

        @Test
        @DisplayName("Valid transition: CREATED -> RUNNING")
        void testStartWorkflowFromCreatedSucceeds() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.CREATED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));
            when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

            WorkflowTask task1 = WorkflowTask.builder().id(11L).name("Task 1").executionOrder(1).workflow(workflow).status(TaskStatus.PENDING).build();
            when(workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(1L)).thenReturn(List.of(task1));

            Workflow started = workflowService.startWorkflow(1L, 100L);

            assertThat(started.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(started.getStartedAt()).isNotNull();
            assertThat(started.getScheduled()).isFalse();
            verify(executionRequestProducer).publishExecutionRequest(any(WorkflowExecutionRequestEvent.class));
            verify(workflowExecutionRepository).save(any(WorkflowExecution.class));
            verify(workflowEventPublisher).publishWorkflowUpdate(any(WorkflowExecutionEvent.class));
        }

        @Test
        @DisplayName("Invalid transition: RUNNING -> RUNNING rejected")
        void testStartWorkflowAlreadyRunningRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.RUNNING);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.startWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot start workflow: workflow is already RUNNING");
            verify(executionRequestProducer, never()).publishExecutionRequest(any());
        }

        @Test
        @DisplayName("Invalid transition: PAUSED -> start rejected (must use resume)")
        void testStartWorkflowPausedRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.PAUSED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.startWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot start workflow: workflow is PAUSED. Use resume instead.");
            verify(executionRequestProducer, never()).publishExecutionRequest(any());
        }

        @Test
        @DisplayName("Invalid transition: COMPLETED -> start rejected (terminal state)")
        void testStartWorkflowCompletedRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.COMPLETED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.startWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot start workflow: workflow is in terminal state COMPLETED");
            verify(executionRequestProducer, never()).publishExecutionRequest(any());
        }

        @Test
        @DisplayName("Invalid transition: FAILED -> start rejected (must use retry)")
        void testStartWorkflowFailedRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.FAILED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.startWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot start workflow: workflow is in terminal state FAILED. Use retry instead.");
            verify(executionRequestProducer, never()).publishExecutionRequest(any());
        }

        @Test
        @DisplayName("Invalid transition: CANCELLED -> start rejected (terminal state)")
        void testStartWorkflowCancelledRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.CANCELLED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.startWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot start workflow: workflow is in terminal state CANCELLED");
            verify(executionRequestProducer, never()).publishExecutionRequest(any());
        }

        @Test
        @DisplayName("Dependency validation: Cannot start workflow if dependent workflow is not COMPLETED")
        void testStartWorkflowUnmetDependencyRejected() {
            Workflow depWorkflow = createMockWorkflow(2L, WorkflowStatus.RUNNING);
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.CREATED);
            workflow.setDependsOnWorkflow(depWorkflow);

            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.startWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot start workflow: dependent workflow 2 is not COMPLETED (current status: RUNNING)");
        }

        @Test
        @DisplayName("Dependency validation: Can start workflow if dependent workflow is COMPLETED")
        void testStartWorkflowMetDependencySucceeds() {
            Workflow depWorkflow = createMockWorkflow(2L, WorkflowStatus.COMPLETED);
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.CREATED);
            workflow.setDependsOnWorkflow(depWorkflow);

            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));
            when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

            WorkflowTask task1 = WorkflowTask.builder().id(11L).name("Task 1").executionOrder(1).workflow(workflow).status(TaskStatus.PENDING).build();
            when(workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(1L)).thenReturn(List.of(task1));

            Workflow started = workflowService.startWorkflow(1L, 100L);
            assertThat(started.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
        }
    }

    // ==========================================
    // 2. CANCEL WORKFLOW TRANSITIONS
    // ==========================================
    @Nested
    @DisplayName("cancelWorkflow State Transitions")
    class CancelWorkflowTransitions {

        @Test
        @DisplayName("Valid transition: RUNNING -> CANCELLED")
        void testCancelRunningWorkflowSucceeds() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.RUNNING);
            workflow.setStartedAt(System.currentTimeMillis() - 5000L);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));
            when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

            WorkflowExecution execution = WorkflowExecution.builder()
                    .workflow(workflow)
                    .status(WorkflowStatus.RUNNING)
                    .startedAt(workflow.getStartedAt())
                    .build();
            when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(1L)).thenReturn(Optional.of(execution));

            Workflow cancelled = workflowService.cancelWorkflow(1L, 100L);

            assertThat(cancelled.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
            assertThat(cancelled.getCompletedAt()).isNotNull();
            assertThat(execution.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
            assertThat(execution.getCompletedAt()).isNotNull();
            assertThat(execution.getDurationMs()).isGreaterThan(0L);

            verify(workflowCancellationProducer).publishWorkflowCancelled(any(WorkflowCancelledEvent.class));
            verify(workflowEventPublisher).publishWorkflowUpdate(any(WorkflowExecutionEvent.class));
        }

        @Test
        @DisplayName("Valid transition: PAUSED -> CANCELLED preserves execution checkpoint")
        void testCancelPausedWorkflowPreservesCheckpoint() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.PAUSED);
            workflow.setStartedAt(System.currentTimeMillis() - 10000L);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));
            when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

            WorkflowExecution execution = WorkflowExecution.builder()
                    .workflow(workflow)
                    .status(WorkflowStatus.PAUSED)
                    .startedAt(workflow.getStartedAt())
                    .lastCompletedExecutionOrder(3) // Checkpoint from 3 completed tasks
                    .build();
            when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(1L)).thenReturn(Optional.of(execution));

            Workflow cancelled = workflowService.cancelWorkflow(1L, 100L);

            assertThat(cancelled.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
            assertThat(execution.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
            // CRITICAL INVARIANT: Checkpoint must be preserved
            assertThat(execution.getLastCompletedExecutionOrder()).isEqualTo(3);
            assertThat(execution.getCompletedAt()).isNotNull();
            verify(workflowCancellationProducer).publishWorkflowCancelled(any(WorkflowCancelledEvent.class));
        }

        @Test
        @DisplayName("Valid transition: CREATED -> CANCELLED clears scheduled flag")
        void testCancelCreatedScheduledWorkflowClearsScheduled() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.CREATED);
            workflow.setScheduled(true);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));
            when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(1L)).thenReturn(Optional.empty());

            Workflow cancelled = workflowService.cancelWorkflow(1L, 100L);

            assertThat(cancelled.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
            assertThat(cancelled.getScheduled()).isFalse();
            verify(workflowCancellationProducer).publishWorkflowCancelled(any(WorkflowCancelledEvent.class));
        }

        @Test
        @DisplayName("Idempotency: CANCELLED -> CANCELLED is no-op")
        void testCancelAlreadyCancelledWorkflowIsIdempotent() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.CANCELLED);
            workflow.setCompletedAt(123456789L);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            Workflow result = workflowService.cancelWorkflow(1L, 100L);

            assertThat(result.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
            assertThat(result.getCompletedAt()).isEqualTo(123456789L);
            verify(workflowRepository, never()).save(any());
            verify(workflowCancellationProducer, never()).publishWorkflowCancelled(any());
        }

        @Test
        @DisplayName("Invalid transition: COMPLETED -> cancel rejected")
        void testCancelCompletedWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.COMPLETED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.cancelWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot cancel workflow: workflow is in terminal state COMPLETED");
            verify(workflowCancellationProducer, never()).publishWorkflowCancelled(any());
        }

        @Test
        @DisplayName("Invalid transition: FAILED -> cancel rejected")
        void testCancelFailedWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.FAILED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.cancelWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot cancel workflow: workflow is in terminal state FAILED");
            verify(workflowCancellationProducer, never()).publishWorkflowCancelled(any());
        }
    }

    // ==========================================
    // 3. PAUSE WORKFLOW TRANSITIONS
    // ==========================================
    @Nested
    @DisplayName("pauseWorkflow State Transitions")
    class PauseWorkflowTransitions {

        @Test
        @DisplayName("Valid transition: RUNNING -> PAUSED")
        void testPauseRunningWorkflowSucceeds() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.RUNNING);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));
            when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Workflow paused = workflowService.pauseWorkflow(1L, 100L);

            assertThat(paused.getStatus()).isEqualTo(WorkflowStatus.PAUSED);
            verify(workflowPauseProducer).publishWorkflowPaused(any(WorkflowPausedEvent.class));
            verify(workflowEventPublisher).publishWorkflowUpdate(any(WorkflowExecutionEvent.class));
        }

        @Test
        @DisplayName("Invalid transition: PAUSED -> pause rejected")
        void testPauseAlreadyPausedRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.PAUSED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.pauseWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot pause workflow: workflow is already PAUSED");
        }

        @Test
        @DisplayName("Invalid transition: CREATED -> pause rejected")
        void testPauseCreatedWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.CREATED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.pauseWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot pause workflow: workflow is in CREATED state");
        }

        @Test
        @DisplayName("Invalid transition: COMPLETED -> pause rejected")
        void testPauseCompletedWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.COMPLETED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.pauseWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot pause workflow: workflow is in terminal state COMPLETED");
        }

        @Test
        @DisplayName("Invalid transition: CANCELLED -> pause rejected")
        void testPauseCancelledWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.CANCELLED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.pauseWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot pause workflow: workflow is in terminal state CANCELLED");
        }

        @Test
        @DisplayName("Invalid transition: FAILED -> pause rejected")
        void testPauseFailedWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.FAILED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.pauseWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot pause workflow: workflow is in terminal state FAILED");
        }
    }

    // ==========================================
    // 4. RESUME WORKFLOW TRANSITIONS
    // ==========================================
    @Nested
    @DisplayName("resumeWorkflow State Transitions")
    class ResumeWorkflowTransitions {

        @Test
        @DisplayName("Valid transition: PAUSED -> RUNNING resumes from checkpoint")
        void testResumePausedWorkflowSucceeds() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.PAUSED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));
            when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

            WorkflowExecution execution = WorkflowExecution.builder()
                    .workflow(workflow)
                    .status(WorkflowStatus.PAUSED)
                    .lastCompletedExecutionOrder(2)
                    .build();
            when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(1L)).thenReturn(Optional.of(execution));

            WorkflowTask task1 = WorkflowTask.builder().id(11L).name("T1").executionOrder(1).workflow(workflow).status(TaskStatus.COMPLETED).build();
            WorkflowTask task2 = WorkflowTask.builder().id(12L).name("T2").executionOrder(2).workflow(workflow).status(TaskStatus.COMPLETED).build();
            WorkflowTask task3 = WorkflowTask.builder().id(13L).name("T3").executionOrder(3).workflow(workflow).status(TaskStatus.PENDING).build();
            when(workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(1L)).thenReturn(List.of(task1, task2, task3));

            Workflow resumed = workflowService.resumeWorkflow(1L, 100L);

            assertThat(resumed.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(execution.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
            verify(workflowResumeProducer).publishWorkflowResumed(any(WorkflowResumedEvent.class));

            ArgumentCaptor<WorkflowExecutionRequestEvent> captor = ArgumentCaptor.forClass(WorkflowExecutionRequestEvent.class);
            verify(executionRequestProducer).publishExecutionRequest(captor.capture());
            assertThat(captor.getValue().getTasks()).hasSize(1);
            assertThat(captor.getValue().getTasks().get(0).getName()).isEqualTo("T3");
        }

        @Test
        @DisplayName("Invalid transition: RUNNING -> resume rejected")
        void testResumeRunningWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.RUNNING);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.resumeWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot resume workflow: workflow is already RUNNING");
        }

        @Test
        @DisplayName("Invalid transition: CREATED -> resume rejected")
        void testResumeCreatedWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.CREATED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.resumeWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot resume workflow: workflow is in CREATED state");
        }

        @Test
        @DisplayName("Invalid transition: COMPLETED -> resume rejected")
        void testResumeCompletedWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.COMPLETED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.resumeWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot resume workflow: workflow is in terminal state COMPLETED");
        }

        @Test
        @DisplayName("Invalid transition: CANCELLED -> resume rejected")
        void testResumeCancelledWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.CANCELLED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.resumeWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot resume workflow: workflow is in terminal state CANCELLED");
        }

        @Test
        @DisplayName("Invalid transition: FAILED -> resume rejected")
        void testResumeFailedWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.FAILED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.resumeWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot resume workflow: workflow is in terminal state FAILED");
        }
    }

    // ==========================================
    // 5. RETRY WORKFLOW TRANSITIONS
    // ==========================================
    @Nested
    @DisplayName("retryWorkflow State Transitions")
    class RetryWorkflowTransitions {

        @Test
        @DisplayName("Valid transition: FAILED -> RUNNING creates new execution record")
        void testRetryFailedWorkflowSucceeds() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.FAILED);
            workflow.setCompletedAt(System.currentTimeMillis() - 1000L);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));
            when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

            WorkflowTask task1 = WorkflowTask.builder().id(11L).name("T1").executionOrder(1).workflow(workflow).status(TaskStatus.FAILED).retryCount(3).build();
            when(workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(1L)).thenReturn(List.of(task1));

            Workflow retried = workflowService.retryWorkflow(1L, 100L);

            assertThat(retried.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(retried.getCompletedAt()).isNull();
            assertThat(task1.getStatus()).isEqualTo(TaskStatus.PENDING);
            assertThat(task1.getRetryCount()).isEqualTo(0);

            verify(workflowExecutionRepository).save(any(WorkflowExecution.class));
            verify(executionRequestProducer).publishExecutionRequest(any(WorkflowExecutionRequestEvent.class));
        }

        @Test
        @DisplayName("Invalid transition: RUNNING -> retry rejected")
        void testRetryRunningWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.RUNNING);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.retryWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot retry workflow: workflow is already RUNNING");
        }

        @Test
        @DisplayName("Invalid transition: PAUSED -> retry rejected")
        void testRetryPausedWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.PAUSED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.retryWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot retry workflow: workflow is PAUSED");
        }

        @Test
        @DisplayName("Invalid transition: CREATED -> retry rejected")
        void testRetryCreatedWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.CREATED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.retryWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot retry workflow: workflow is in CREATED state");
        }

        @Test
        @DisplayName("Invalid transition: COMPLETED -> retry rejected")
        void testRetryCompletedWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.COMPLETED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.retryWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot retry workflow: workflow is in terminal state COMPLETED");
        }

        @Test
        @DisplayName("Invalid transition: CANCELLED -> retry rejected")
        void testRetryCancelledWorkflowRejected() {
            Workflow workflow = createMockWorkflow(1L, WorkflowStatus.CANCELLED);
            when(workflowRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() -> workflowService.retryWorkflow(1L, 100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot retry workflow: workflow is in terminal state CANCELLED");
        }
    }

    // ==========================================
    // 6. KAFKA EVENT CONSUMER STATE GUARDS
    // ==========================================
    @Nested
    @DisplayName("ExecutionEventConsumer Late Event Protection")
    class ExecutionEventConsumerGuards {

        private ExecutionEventConsumer consumer;

        @BeforeEach
        void setUpConsumer() {
            consumer = new ExecutionEventConsumer(
                    workflowRepository,
                    workflowTaskRepository,
                    workflowExecutionRepository
            );
        }

        @Test
        @DisplayName("Late task-started event is ignored when workflow is CANCELLED")
        void testLateTaskStartedIgnoredOnCancelledWorkflow() {
            Workflow cancelledWorkflow = createMockWorkflow(1L, WorkflowStatus.CANCELLED);
            when(workflowRepository.findById(1L)).thenReturn(Optional.of(cancelledWorkflow));

            TaskStartedEvent event = TaskStartedEvent.builder()
                    .workflowId(1L)
                    .workflowName("W1")
                    .taskName("T1")
                    .executionOrder(1)
                    .build();
            WorkflowTask task = WorkflowTask.builder().id(11L).name("T1").executionOrder(1).workflow(cancelledWorkflow).status(TaskStatus.PENDING).build();
            when(workflowTaskRepository.findByWorkflowIdAndExecutionOrder(1L, 1)).thenReturn(Optional.of(task));

            consumer.consumeTaskStarted(event);

            verify(workflowTaskRepository, never()).save(any());
            assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        }

        @Test
        @DisplayName("Late task-started event is ignored when workflow is PAUSED")
        void testLateTaskStartedIgnoredOnPausedWorkflow() {
            Workflow pausedWorkflow = createMockWorkflow(1L, WorkflowStatus.PAUSED);
            when(workflowRepository.findById(1L)).thenReturn(Optional.of(pausedWorkflow));

            TaskStartedEvent event = TaskStartedEvent.builder()
                    .workflowId(1L)
                    .workflowName("W1")
                    .taskName("T1")
                    .executionOrder(1)
                    .build();
            WorkflowTask task = WorkflowTask.builder().id(11L).name("T1").executionOrder(1).workflow(pausedWorkflow).status(TaskStatus.PENDING).build();
            when(workflowTaskRepository.findByWorkflowIdAndExecutionOrder(1L, 1)).thenReturn(Optional.of(task));

            consumer.consumeTaskStarted(event);

            verify(workflowTaskRepository, never()).save(any());
            assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        }

        @Test
        @DisplayName("Late workflow-completed event is ignored when workflow is CANCELLED")
        void testLateWorkflowCompletedIgnoredOnCancelledWorkflow() {
            Workflow cancelledWorkflow = createMockWorkflow(1L, WorkflowStatus.CANCELLED);
            when(workflowRepository.findById(1L)).thenReturn(Optional.of(cancelledWorkflow));

            WorkflowCompletedEvent event = WorkflowCompletedEvent.builder()
                    .workflowId(1L)
                    .workflowName("W1")
                    .success(true)
                    .build();
            consumer.consumeWorkflowCompleted(event);

            verify(workflowRepository, never()).save(any());
            verify(workflowExecutionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Late workflow-completed event is ignored when workflow is PAUSED")
        void testLateWorkflowCompletedIgnoredOnPausedWorkflow() {
            Workflow pausedWorkflow = createMockWorkflow(1L, WorkflowStatus.PAUSED);
            when(workflowRepository.findById(1L)).thenReturn(Optional.of(pausedWorkflow));

            WorkflowCompletedEvent event = WorkflowCompletedEvent.builder()
                    .workflowId(1L)
                    .workflowName("W1")
                    .success(true)
                    .build();
            consumer.consumeWorkflowCompleted(event);

            verify(workflowRepository, never()).save(any());
            verify(workflowExecutionRepository, never()).save(any());
        }
    }
}
