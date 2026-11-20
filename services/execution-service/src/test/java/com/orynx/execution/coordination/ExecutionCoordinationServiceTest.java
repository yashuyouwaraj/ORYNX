package com.orynx.execution.coordination;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionCoordinationServiceTest {

    @Mock
    private WorkflowExecutionCoordinationRepository repository;

    private ExecutionCoordinationService service;

    @BeforeEach
    void setUp() {
        service = new ExecutionCoordinationService(repository);
    }

    @Test
    void shouldAcquireInitialLeaseWithGenerationOne() {
        when(repository.findByWorkflowIdForUpdate(1L)).thenReturn(Optional.empty());
        when(repository.save(any(WorkflowExecutionCoordination.class))).thenAnswer(i -> i.getArgument(0));

        ExecutionLeaseAcquisitionResult result = service.tryAcquireLease(1L, "Sample Workflow", "node-1", 300L, "{}");

        assertThat(result.isAcquired()).isTrue();
        assertThat(result.getStatus()).isEqualTo(ExecutionLeaseAcquisitionResult.Status.ACQUIRED);
        assertThat(result.getGeneration()).isEqualTo(1L);

        ArgumentCaptor<WorkflowExecutionCoordination> captor = ArgumentCaptor.forClass(WorkflowExecutionCoordination.class);
        verify(repository).save(captor.capture());
        WorkflowExecutionCoordination saved = captor.getValue();
        assertThat(saved.getWorkflowId()).isEqualTo(1L);
        assertThat(saved.getGeneration()).isEqualTo(1L);
        assertThat(saved.getStatus()).isEqualTo(ExecutionCoordinationStatus.DISPATCHED);
        assertThat(saved.getInstanceId()).isEqualTo("node-1");
    }

    @Test
    void shouldRejectDuplicateExecutionWhenActivelyRunningWithValidLease() {
        WorkflowExecutionCoordination existing = WorkflowExecutionCoordination.builder()
                .workflowId(2L)
                .workflowName("Active Job")
                .status(ExecutionCoordinationStatus.RUNNING)
                .instanceId("node-1")
                .generation(1L)
                .acquiredAt(Instant.now().minusSeconds(10))
                .leaseExpiresAt(Instant.now().plusSeconds(290))
                .build();

        when(repository.findByWorkflowIdForUpdate(2L)).thenReturn(Optional.of(existing));

        ExecutionLeaseAcquisitionResult result = service.tryAcquireLease(2L, "Active Job", "node-2", 300L);

        assertThat(result.isAcquired()).isFalse();
        assertThat(result.getStatus()).isEqualTo(ExecutionLeaseAcquisitionResult.Status.DUPLICATE_ACTIVE);
        verify(repository, never()).save(any());
    }

    @Test
    void shouldReclaimExpiredLeaseFromCrashedWorkerAndIncrementGeneration() {
        // Lease expired 30 seconds ago on crashed node-1 with generation 1
        WorkflowExecutionCoordination expired = WorkflowExecutionCoordination.builder()
                .workflowId(6L)
                .workflowName("Crashed Job")
                .status(ExecutionCoordinationStatus.RUNNING)
                .instanceId("crashed-node-1")
                .generation(1L)
                .recoveryAttempts(0)
                .acquiredAt(Instant.now().minusSeconds(400))
                .leaseExpiresAt(Instant.now().minusSeconds(30))
                .build();

        when(repository.findByWorkflowIdForUpdate(6L)).thenReturn(Optional.of(expired));
        when(repository.save(any(WorkflowExecutionCoordination.class))).thenAnswer(i -> i.getArgument(0));

        ExecutionLeaseAcquisitionResult result = service.tryAcquireLease(6L, "Crashed Job", "recovering-node-2", 300L);

        assertThat(result.isAcquired()).isTrue();
        assertThat(result.getStatus()).isEqualTo(ExecutionLeaseAcquisitionResult.Status.ACQUIRED);
        assertThat(result.getGeneration()).isEqualTo(2L);

        ArgumentCaptor<WorkflowExecutionCoordination> captor = ArgumentCaptor.forClass(WorkflowExecutionCoordination.class);
        verify(repository).save(captor.capture());
        WorkflowExecutionCoordination saved = captor.getValue();
        assertThat(saved.getInstanceId()).isEqualTo("recovering-node-2");
        assertThat(saved.getGeneration()).isEqualTo(2L);
        assertThat(saved.getStatus()).isEqualTo(ExecutionCoordinationStatus.DISPATCHED);
        assertThat(saved.getLeaseExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void shouldReclaimLeaseForRetryOfFailedWorkflowAndIncrementGeneration() {
        WorkflowExecutionCoordination failed = WorkflowExecutionCoordination.builder()
                .workflowId(7L)
                .workflowName("Failed Job")
                .status(ExecutionCoordinationStatus.FAILED)
                .instanceId("node-1")
                .generation(2L)
                .acquiredAt(Instant.now().minusSeconds(100))
                .build();

        when(repository.findByWorkflowIdForUpdate(7L)).thenReturn(Optional.of(failed));
        when(repository.save(any(WorkflowExecutionCoordination.class))).thenAnswer(i -> i.getArgument(0));

        ExecutionLeaseAcquisitionResult result = service.tryAcquireLease(7L, "Failed Job", "node-2", 300L);

        assertThat(result.isAcquired()).isTrue();
        assertThat(result.getStatus()).isEqualTo(ExecutionLeaseAcquisitionResult.Status.ACQUIRED);
        assertThat(result.getGeneration()).isEqualTo(3L);
        verify(repository).save(any());
    }

    @Test
    void shouldExtendLeaseDuringHeartbeatWhenOwnershipMatches() {
        when(repository.updateHeartbeat(eq(100L), eq(1L), eq("node-1"), any(), any()))
                .thenReturn(1);

        boolean renewed = service.heartbeat(100L, 1L, "node-1", 300L);
        assertThat(renewed).isTrue();
    }

    @Test
    void shouldRejectHeartbeatWhenLeaseFencedOrStolen() {
        // Return 0 rows updated because generation or instanceId changed
        when(repository.updateHeartbeat(eq(100L), eq(1L), eq("node-1"), any(), any()))
                .thenReturn(0);

        boolean renewed = service.heartbeat(100L, 1L, "node-1", 300L);
        assertThat(renewed).isFalse();
    }

    @Test
    void shouldThrowStaleWorkerExceptionWhenFencedWorkerAttemptsToMarkCompletion() {
        when(repository.markCompletedFenced(eq(200L), eq(1L), eq("node-1"), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.markCompleted(200L, 1L, "node-1", true))
                .isInstanceOf(StaleWorkerExecutionException.class);
    }

    @Test
    void shouldReclaimAbandonedExecutionForRecovery() {
        WorkflowExecutionCoordination abandoned = WorkflowExecutionCoordination.builder()
                .workflowId(300L)
                .workflowName("Abandoned Job")
                .status(ExecutionCoordinationStatus.RUNNING)
                .instanceId("crashed-node")
                .generation(1L)
                .recoveryAttempts(1)
                .leaseExpiresAt(Instant.now().minusSeconds(10))
                .payloadJson("{\"workflowId\":300}")
                .build();

        when(repository.findByWorkflowIdForUpdate(300L)).thenReturn(Optional.of(abandoned));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        ExecutionCoordinationService.RecoveryResult result = service.reclaimForRecovery(300L, "recovering-node", 300L, 3);

        assertThat(result.outcome()).isEqualTo(ExecutionCoordinationService.RecoveryOutcome.RECLAIMED);
        assertThat(result.coordination().getGeneration()).isEqualTo(2L);
        assertThat(result.coordination().getRecoveryAttempts()).isEqualTo(2);
        assertThat(result.coordination().getInstanceId()).isEqualTo("recovering-node");
    }

    @Test
    void shouldMarkFailedWhenAbandonedExecutionExceedsMaxRecoveryAttempts() {
        WorkflowExecutionCoordination maxedOut = WorkflowExecutionCoordination.builder()
                .workflowId(400L)
                .workflowName("Failing Job")
                .status(ExecutionCoordinationStatus.RUNNING)
                .instanceId("crashed-node")
                .generation(3L)
                .recoveryAttempts(3)
                .leaseExpiresAt(Instant.now().minusSeconds(10))
                .build();

        when(repository.findByWorkflowIdForUpdate(400L)).thenReturn(Optional.of(maxedOut));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        ExecutionCoordinationService.RecoveryResult result = service.reclaimForRecovery(400L, "recovering-node", 300L, 3);

        assertThat(result.outcome()).isEqualTo(ExecutionCoordinationService.RecoveryOutcome.FAILED_EXCEEDED_ATTEMPTS);
        assertThat(result.coordination().getStatus()).isEqualTo(ExecutionCoordinationStatus.FAILED);
    }
}
