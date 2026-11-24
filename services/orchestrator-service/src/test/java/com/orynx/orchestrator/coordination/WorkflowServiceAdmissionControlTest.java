package com.orynx.orchestrator.coordination;

import com.orynx.orchestrator.coordination.admission.AdmissionDecision;
import com.orynx.orchestrator.coordination.admission.WorkflowAdmissionController;
import com.orynx.orchestrator.coordination.admission.WorkflowAdmissionException;
import com.orynx.orchestrator.execution.ExecutionEventConsumer;
import com.orynx.orchestrator.execution.WorkflowExecution;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.execution.event.WorkflowCompletedEvent;
import com.orynx.orchestrator.event.dto.WorkflowExecutionPausedEvent;
import com.orynx.orchestrator.kafka.*;
import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowEventPublisher;
import com.orynx.orchestrator.workflow.WorkflowRepository;
import com.orynx.orchestrator.workflow.WorkflowService;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import com.orynx.orchestrator.workflow.task.WorkflowExecutionEngine;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceAdmissionControlTest {

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
    @Mock
    private WorkflowAdmissionController admissionController;

    private WorkflowService workflowService;
    private ExecutionEventConsumer executionEventConsumer;

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
        workflowService.setWorkflowAdmissionController(admissionController);

        executionEventConsumer = new ExecutionEventConsumer(
                workflowRepository,
                workflowTaskRepository,
                workflowExecutionRepository,
                workflowEventPublisher
        );
        executionEventConsumer.setWorkflowAdmissionController(admissionController);
    }

    @Test
    @DisplayName("startWorkflow requests admission and succeeds when admitted")
    void startWorkflowRequestsAdmissionAndSucceeds() {
        Long workflowId = 100L;
        Long userId = 42L;

        Workflow workflow = Workflow.builder()
                .id(workflowId)
                .userId(userId)
                .name("test-wf")
                .status(WorkflowStatus.CREATED)
                .build();

        when(workflowRepository.findByIdAndUserIdForUpdate(workflowId, userId))
                .thenReturn(Optional.of(workflow));
        when(admissionController.tryAdmit(workflowId, userId))
                .thenReturn(AdmissionDecision.admitted("ADMITTED", 1, 1, "test-token"));
        when(workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(workflowId))
                .thenReturn(List.of(WorkflowTask.builder().id(1L).workflow(workflow).build()));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        workflowService.startWorkflow(workflowId, userId);

        verify(admissionController, times(1)).tryAdmit(workflowId, userId);
        verify(workflowRepository, times(1)).save(workflow);
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
    }

    @Test
    @DisplayName("startWorkflow throws WorkflowAdmissionException when capacity is exceeded without modifying workflow state")
    void startWorkflowRejectsWhenCapacityExceeded() {
        Long workflowId = 101L;
        Long userId = 42L;

        Workflow workflow = Workflow.builder()
                .id(workflowId)
                .userId(userId)
                .name("test-wf-rejected")
                .status(WorkflowStatus.CREATED)
                .build();

        when(workflowRepository.findByIdAndUserIdForUpdate(workflowId, userId))
                .thenReturn(Optional.of(workflow));
        when(admissionController.tryAdmit(workflowId, userId))
                .thenThrow(new WorkflowAdmissionException(
                        "Global concurrent workflow limit reached",
                        HttpStatus.TOO_MANY_REQUESTS,
                        "GLOBAL_LIMIT_EXCEEDED",
                        15
                ));

        assertThatThrownBy(() -> workflowService.startWorkflow(workflowId, userId))
                .isInstanceOf(WorkflowAdmissionException.class)
                .hasMessageContaining("Global concurrent workflow limit reached");

        // Verify status was NOT modified and repository was NOT saved
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.CREATED);
        verify(workflowRepository, never()).save(any());
        verify(executionRequestProducer, never()).publishExecutionRequest(any());
    }

    @Test
    @DisplayName("cancelWorkflow releases admission slot")
    void cancelWorkflowReleasesAdmissionSlot() {
        Long workflowId = 102L;
        Long userId = 42L;

        Workflow workflow = Workflow.builder()
                .id(workflowId)
                .userId(userId)
                .name("test-wf-cancel")
                .status(WorkflowStatus.RUNNING)
                .build();

        when(workflowRepository.findByIdAndUserIdForUpdate(workflowId, userId))
                .thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        workflowService.cancelWorkflow(workflowId, userId);

        verify(admissionController, times(1)).release(workflowId, userId);
        verify(workflowRepository, times(1)).save(workflow);
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
    }

    @Test
    @DisplayName("pauseWorkflow releases admission slot")
    void pauseWorkflowReleasesAdmissionSlot() {
        Long workflowId = 103L;
        Long userId = 42L;

        Workflow workflow = Workflow.builder()
                .id(workflowId)
                .userId(userId)
                .name("test-wf-pause")
                .status(WorkflowStatus.RUNNING)
                .build();

        when(workflowRepository.findByIdAndUserIdForUpdate(workflowId, userId))
                .thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        workflowService.pauseWorkflow(workflowId, userId);

        verify(admissionController, times(1)).release(workflowId, userId);
        verify(workflowRepository, times(1)).save(workflow);
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.PAUSED);
    }

    @Test
    @DisplayName("resumeWorkflow requests admission slot")
    void resumeWorkflowRequestsAdmissionSlot() {
        Long workflowId = 104L;
        Long userId = 42L;

        Workflow workflow = Workflow.builder()
                .id(workflowId)
                .userId(userId)
                .name("test-wf-resume")
                .status(WorkflowStatus.PAUSED)
                .build();

        WorkflowExecution execution = WorkflowExecution.builder()
                .id(55L)
                .workflow(workflow)
                .status(WorkflowStatus.PAUSED)
                .executionGeneration(1L)
                .build();

        when(workflowRepository.findByIdAndUserIdForUpdate(workflowId, userId))
                .thenReturn(Optional.of(workflow));
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(workflowId))
                .thenReturn(Optional.of(execution));
        when(workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(workflowId))
                .thenReturn(List.of(WorkflowTask.builder().id(10L).executionOrder(1).workflow(workflow).build()));
        when(admissionController.tryAdmit(workflowId, userId))
                .thenReturn(AdmissionDecision.admitted("ADMITTED", 1, 1, "test-token"));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        workflowService.resumeWorkflow(workflowId, userId);

        verify(admissionController, times(1)).tryAdmit(workflowId, userId);
        verify(workflowRepository, times(1)).save(workflow);
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
    }

    @Test
    @DisplayName("ExecutionEventConsumer releases admission slot on workflow completion")
    void executionEventConsumerReleasesOnCompletion() {
        Long workflowId = 105L;
        Long userId = 99L;

        Workflow workflow = Workflow.builder()
                .id(workflowId)
                .userId(userId)
                .name("test-wf-completed")
                .status(WorkflowStatus.RUNNING)
                .build();

        when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowCompletedEvent event = new WorkflowCompletedEvent();
        event.setWorkflowId(workflowId);
        event.setWorkflowName("test-wf-completed");
        event.setSuccess(true);
        event.setGeneration(1L);

        executionEventConsumer.consumeWorkflowCompleted(event);

        verify(admissionController, times(1)).release(workflowId, userId);
        verify(workflowRepository, times(1)).save(workflow);
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
    }

    @Test
    @DisplayName("ExecutionEventConsumer releases admission slot on workflow execution paused")
    void executionEventConsumerReleasesOnPaused() {
        Long workflowId = 106L;
        Long userId = 99L;

        Workflow workflow = Workflow.builder()
                .id(workflowId)
                .userId(userId)
                .name("test-wf-paused-event")
                .status(WorkflowStatus.RUNNING)
                .build();

        WorkflowExecution execution = WorkflowExecution.builder()
                .id(66L)
                .workflow(workflow)
                .status(WorkflowStatus.RUNNING)
                .executionGeneration(1L)
                .build();

        when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(workflow));
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(workflowId))
                .thenReturn(Optional.of(execution));
        when(workflowExecutionRepository.save(any(WorkflowExecution.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowExecutionPausedEvent event = new WorkflowExecutionPausedEvent();
        event.setWorkflowId(workflowId);
        event.setWorkflowName("test-wf-paused-event");
        event.setGeneration(1L);

        executionEventConsumer.consumeWorkflowExecutionPaused(event);

        verify(admissionController, times(1)).release(workflowId, userId);
        verify(workflowExecutionRepository, times(1)).save(execution);
        assertThat(execution.getStatus()).isEqualTo(WorkflowStatus.PAUSED);
    }

    @Test
    @DisplayName("startWorkflow rolls back admission slot if subsequent database or dispatch operation fails")
    void startWorkflowRollsBackAdmissionIfSubsequentOperationFails() {
        Long workflowId = 109L;
        Long userId = 42L;

        Workflow workflow = Workflow.builder()
                .id(workflowId)
                .userId(userId)
                .name("test-wf-failure-rollback")
                .status(WorkflowStatus.CREATED)
                .build();

        when(workflowRepository.findByIdAndUserIdForUpdate(workflowId, userId))
                .thenReturn(Optional.of(workflow));
        when(admissionController.tryAdmit(workflowId, userId))
                .thenReturn(AdmissionDecision.admitted("ADMITTED", 1, 1, "test-token"));
        when(workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(workflowId))
                .thenReturn(List.of(WorkflowTask.builder().id(1L).workflow(workflow).build()));

        // Simulate database save failure after admission
        when(workflowRepository.save(any(Workflow.class)))
                .thenThrow(new RuntimeException("Database constraint violation"));

        assertThatThrownBy(() -> workflowService.startWorkflow(workflowId, userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database constraint violation");

        // Verify admission was requested AND then rolled back / released
        verify(admissionController, times(1)).tryAdmit(workflowId, userId);
        verify(admissionController, times(1)).release(workflowId, userId);
    }
}
