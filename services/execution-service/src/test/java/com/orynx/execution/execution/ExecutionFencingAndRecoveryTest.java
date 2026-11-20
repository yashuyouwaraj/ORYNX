package com.orynx.execution.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.coordination.ExecutionCoordinationStatus;
import com.orynx.execution.coordination.WorkflowExecutionCoordination;
import com.orynx.execution.event.dto.WorkflowCompletedEvent;
import com.orynx.execution.kafka.ExecutionEventProducer;
import com.orynx.execution.recovery.ExecutionRecoveryScanner;
import com.orynx.execution.worker.ExecutionWorkerProperties;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.execution.workflow.event.dto.TaskExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionFencingAndRecoveryTest {

    @Mock
    private ExecutionEventProducer executionEventProducer;

    @Mock
    private ExecutionCoordinationService coordinationService;

    @Mock
    private WorkflowExecutionDispatcher dispatcher;

    private ActiveExecutionTracker activeExecutionTracker;
    private WorkflowCancellationRegistry cancellationRegistry;
    private WorkflowPauseRegistry pauseRegistry;
    private ObjectMapper objectMapper;
    private ExecutionWorkerProperties properties;

    @BeforeEach
    void setUp() {
        activeExecutionTracker = new ActiveExecutionTracker();
        cancellationRegistry = new WorkflowCancellationRegistry();
        pauseRegistry = new WorkflowPauseRegistry();
        objectMapper = new ObjectMapper();
        properties = new ExecutionWorkerProperties();
    }

    @Test
    void shouldSuppressAllKafkaEventsWhenWorkerIsFencedByHigherGeneration() {
        TaskExecutor taskExecutor = new TaskExecutor(() -> true, 0L);
        WorkflowExecutionEngine engine = new WorkflowExecutionEngine(
                taskExecutor,
                executionEventProducer,
                cancellationRegistry,
                pauseRegistry,
                coordinationService,
                activeExecutionTracker
        );

        Long workflowId = 55L;
        Long staleGeneration = 1L;
        String instanceId = "stale-node-1";

        // Coordination service reports this generation is fenced
        when(coordinationService.isFenced(workflowId, staleGeneration, instanceId)).thenReturn(true);

        WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                .workflowId(workflowId)
                .workflowName("Fenced Pipeline")
                .tasks(List.of(TaskExecutionRequest.builder().name("Task 1").executionOrder(1).build()))
                .build();

        engine.execute(event, staleGeneration, instanceId);

        // Stale worker must NOT publish task started, completed, or workflow completed events!
        verify(executionEventProducer, never()).publishTaskStarted(any());
        verify(executionEventProducer, never()).publishTaskCompleted(any());
        verify(executionEventProducer, never()).publishWorkflowCompleted(any());
    }

    @Test
    void shouldRecoverAbandonedExecutionAndRedispatchViaRecoveryScanner() throws Exception {
        ExecutionRecoveryScanner scanner = new ExecutionRecoveryScanner(
                coordinationService,
                dispatcher,
                executionEventProducer,
                properties,
                objectMapper
        );

        Long workflowId = 777L;
        WorkflowExecutionRequestEvent originalEvent = WorkflowExecutionRequestEvent.builder()
                .workflowId(workflowId)
                .workflowName("Recovered Pipeline")
                .tasks(List.of(TaskExecutionRequest.builder().name("Task 1").executionOrder(1).build()))
                .build();
        String payloadJson = objectMapper.writeValueAsString(originalEvent);

        WorkflowExecutionCoordination abandoned = WorkflowExecutionCoordination.builder()
                .workflowId(workflowId)
                .workflowName("Recovered Pipeline")
                .status(ExecutionCoordinationStatus.DISPATCHED)
                .instanceId("crashed-instance")
                .generation(1L)
                .recoveryAttempts(0)
                .payloadJson(payloadJson)
                .build();

        when(coordinationService.findAbandonedExecutions(any()))
                .thenReturn(List.of(abandoned));

        WorkflowExecutionCoordination reclaimed = WorkflowExecutionCoordination.builder()
                .workflowId(workflowId)
                .workflowName("Recovered Pipeline")
                .status(ExecutionCoordinationStatus.DISPATCHED)
                .instanceId("scanner-instance")
                .generation(2L)
                .recoveryAttempts(1)
                .payloadJson(payloadJson)
                .build();

        when(coordinationService.reclaimForRecovery(eq(workflowId), any(), anyLong(), anyInt()))
                .thenReturn(new ExecutionCoordinationService.RecoveryResult(
                        ExecutionCoordinationService.RecoveryOutcome.RECLAIMED,
                        reclaimed
                ));

        scanner.scanAndRecoverAbandonedExecutions();

        ArgumentCaptor<WorkflowExecutionRequestEvent> eventCaptor = ArgumentCaptor.forClass(WorkflowExecutionRequestEvent.class);
        verify(dispatcher).dispatch(eventCaptor.capture(), eq(2L));
        assertThat(eventCaptor.getValue().getWorkflowId()).isEqualTo(workflowId);
        verify(executionEventProducer, never()).publishWorkflowCompleted(any());
    }

    @Test
    void shouldPublishFailureWhenAbandonedExecutionExceedsMaxRecoveryAttempts() {
        ExecutionRecoveryScanner scanner = new ExecutionRecoveryScanner(
                coordinationService,
                dispatcher,
                executionEventProducer,
                properties,
                objectMapper
        );

        Long workflowId = 888L;
        WorkflowExecutionCoordination abandoned = WorkflowExecutionCoordination.builder()
                .workflowId(workflowId)
                .workflowName("Exhausted Pipeline")
                .status(ExecutionCoordinationStatus.RUNNING)
                .instanceId("crashed-instance")
                .generation(3L)
                .recoveryAttempts(3)
                .build();

        when(coordinationService.findAbandonedExecutions(any()))
                .thenReturn(List.of(abandoned));

        when(coordinationService.reclaimForRecovery(eq(workflowId), any(), anyLong(), anyInt()))
                .thenReturn(new ExecutionCoordinationService.RecoveryResult(
                        ExecutionCoordinationService.RecoveryOutcome.FAILED_EXCEEDED_ATTEMPTS,
                        abandoned
                ));

        scanner.scanAndRecoverAbandonedExecutions();

        verify(dispatcher, never()).dispatch(any(), any());
        ArgumentCaptor<WorkflowCompletedEvent> captor = ArgumentCaptor.forClass(WorkflowCompletedEvent.class);
        verify(executionEventProducer).publishWorkflowCompleted(captor.capture());
        assertThat(captor.getValue().getWorkflowId()).isEqualTo(workflowId);
        assertThat(captor.getValue().isSuccess()).isFalse();
    }
}
