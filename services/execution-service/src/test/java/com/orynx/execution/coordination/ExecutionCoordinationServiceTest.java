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
    void shouldAcquireInitialLeaseWhenNoCoordinationRecordExists() {
        when(repository.findByWorkflowIdForUpdate(1L)).thenReturn(Optional.empty());
        when(repository.save(any(WorkflowExecutionCoordination.class))).thenAnswer(i -> i.getArgument(0));

        ExecutionLeaseAcquisitionResult result = service.tryAcquireLease(1L, "Sample Workflow", "node-1", 300L);

        assertThat(result.isAcquired()).isTrue();
        assertThat(result.getStatus()).isEqualTo(ExecutionLeaseAcquisitionResult.Status.ACQUIRED);

        ArgumentCaptor<WorkflowExecutionCoordination> captor = ArgumentCaptor.forClass(WorkflowExecutionCoordination.class);
        verify(repository).save(captor.capture());
        WorkflowExecutionCoordination saved = captor.getValue();
        assertThat(saved.getWorkflowId()).isEqualTo(1L);
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
    void shouldRejectDuplicateExecutionWhenAlreadyInTerminalCompletedState() {
        WorkflowExecutionCoordination existing = WorkflowExecutionCoordination.builder()
                .workflowId(3L)
                .workflowName("Completed Job")
                .status(ExecutionCoordinationStatus.COMPLETED)
                .instanceId("node-1")
                .acquiredAt(Instant.now().minusSeconds(100))
                .build();

        when(repository.findByWorkflowIdForUpdate(3L)).thenReturn(Optional.of(existing));

        ExecutionLeaseAcquisitionResult result = service.tryAcquireLease(3L, "Completed Job", "node-2", 300L);

        assertThat(result.isAcquired()).isFalse();
        assertThat(result.getStatus()).isEqualTo(ExecutionLeaseAcquisitionResult.Status.DUPLICATE_TERMINAL);
        verify(repository, never()).save(any());
    }

    @Test
    void shouldRejectDuplicateExecutionWhenAlreadyInTerminalCancelledState() {
        WorkflowExecutionCoordination existing = WorkflowExecutionCoordination.builder()
                .workflowId(4L)
                .workflowName("Cancelled Job")
                .status(ExecutionCoordinationStatus.CANCELLED)
                .instanceId("node-1")
                .acquiredAt(Instant.now().minusSeconds(100))
                .build();

        when(repository.findByWorkflowIdForUpdate(4L)).thenReturn(Optional.of(existing));

        ExecutionLeaseAcquisitionResult result = service.tryAcquireLease(4L, "Cancelled Job", "node-2", 300L);

        assertThat(result.isAcquired()).isFalse();
        assertThat(result.getStatus()).isEqualTo(ExecutionLeaseAcquisitionResult.Status.DUPLICATE_TERMINAL);
        verify(repository, never()).save(any());
    }

    @Test
    void shouldRejectExecutionWhenWorkflowIsPausedWithoutResume() {
        WorkflowExecutionCoordination existing = WorkflowExecutionCoordination.builder()
                .workflowId(5L)
                .workflowName("Paused Job")
                .status(ExecutionCoordinationStatus.PAUSED)
                .instanceId("node-1")
                .lastCompletedExecutionOrder(2)
                .acquiredAt(Instant.now().minusSeconds(100))
                .build();

        when(repository.findByWorkflowIdForUpdate(5L)).thenReturn(Optional.of(existing));

        ExecutionLeaseAcquisitionResult result = service.tryAcquireLease(5L, "Paused Job", "node-2", 300L);

        assertThat(result.isAcquired()).isFalse();
        assertThat(result.getStatus()).isEqualTo(ExecutionLeaseAcquisitionResult.Status.PAUSED);
        verify(repository, never()).save(any());
    }

    @Test
    void shouldReclaimExpiredLeaseFromCrashedWorkerInstance() {
        // Lease expired 30 seconds ago on crashed node-1
        WorkflowExecutionCoordination expired = WorkflowExecutionCoordination.builder()
                .workflowId(6L)
                .workflowName("Crashed Job")
                .status(ExecutionCoordinationStatus.RUNNING)
                .instanceId("crashed-node-1")
                .acquiredAt(Instant.now().minusSeconds(400))
                .leaseExpiresAt(Instant.now().minusSeconds(30))
                .build();

        when(repository.findByWorkflowIdForUpdate(6L)).thenReturn(Optional.of(expired));
        when(repository.save(any(WorkflowExecutionCoordination.class))).thenAnswer(i -> i.getArgument(0));

        ExecutionLeaseAcquisitionResult result = service.tryAcquireLease(6L, "Crashed Job", "recovering-node-2", 300L);

        assertThat(result.isAcquired()).isTrue();
        assertThat(result.getStatus()).isEqualTo(ExecutionLeaseAcquisitionResult.Status.ACQUIRED);

        ArgumentCaptor<WorkflowExecutionCoordination> captor = ArgumentCaptor.forClass(WorkflowExecutionCoordination.class);
        verify(repository).save(captor.capture());
        WorkflowExecutionCoordination saved = captor.getValue();
        assertThat(saved.getInstanceId()).isEqualTo("recovering-node-2");
        assertThat(saved.getStatus()).isEqualTo(ExecutionCoordinationStatus.DISPATCHED);
        assertThat(saved.getLeaseExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void shouldReclaimLeaseForRetryOfFailedWorkflow() {
        WorkflowExecutionCoordination failed = WorkflowExecutionCoordination.builder()
                .workflowId(7L)
                .workflowName("Failed Job")
                .status(ExecutionCoordinationStatus.FAILED)
                .instanceId("node-1")
                .acquiredAt(Instant.now().minusSeconds(100))
                .build();

        when(repository.findByWorkflowIdForUpdate(7L)).thenReturn(Optional.of(failed));
        when(repository.save(any(WorkflowExecutionCoordination.class))).thenAnswer(i -> i.getArgument(0));

        ExecutionLeaseAcquisitionResult result = service.tryAcquireLease(7L, "Failed Job", "node-2", 300L);

        assertThat(result.isAcquired()).isTrue();
        assertThat(result.getStatus()).isEqualTo(ExecutionLeaseAcquisitionResult.Status.ACQUIRED);
        verify(repository).save(any());
    }

    @Test
    void shouldReleaseLeaseOnDispatchFailure() {
        WorkflowExecutionCoordination dispatched = WorkflowExecutionCoordination.builder()
                .workflowId(8L)
                .status(ExecutionCoordinationStatus.DISPATCHED)
                .build();

        when(repository.findByWorkflowIdForUpdate(8L)).thenReturn(Optional.of(dispatched));

        service.releaseLeaseOnDispatchFailure(8L);

        verify(repository).delete(dispatched);
    }
}
