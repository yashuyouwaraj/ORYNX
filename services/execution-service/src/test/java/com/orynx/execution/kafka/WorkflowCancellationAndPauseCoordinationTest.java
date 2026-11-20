package com.orynx.execution.kafka;

import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.event.dto.WorkflowCancelledEvent;
import com.orynx.execution.event.dto.WorkflowPausedEvent;
import com.orynx.execution.event.dto.WorkflowResumedEvent;
import com.orynx.execution.execution.ActiveExecutionTracker;
import com.orynx.execution.execution.WorkflowCancellationRegistry;
import com.orynx.execution.execution.WorkflowPauseRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkflowCancellationAndPauseCoordinationTest {

    @Mock
    private ExecutionCoordinationService coordinationService;

    @Mock
    private Acknowledgment acknowledgment;

    private WorkflowCancellationRegistry cancellationRegistry;
    private WorkflowPauseRegistry pauseRegistry;
    private ActiveExecutionTracker activeExecutionTracker;

    private WorkflowCancellationConsumer cancellationConsumer;
    private WorkflowPauseConsumer pauseConsumer;
    private WorkflowResumeConsumer resumeConsumer;

    @BeforeEach
    void setUp() {
        cancellationRegistry = new WorkflowCancellationRegistry();
        pauseRegistry = new WorkflowPauseRegistry();
        activeExecutionTracker = new ActiveExecutionTracker();

        cancellationConsumer = new WorkflowCancellationConsumer(
                cancellationRegistry,
                activeExecutionTracker,
                coordinationService
        );

        pauseConsumer = new WorkflowPauseConsumer(
                pauseRegistry,
                activeExecutionTracker,
                coordinationService
        );

        resumeConsumer = new WorkflowResumeConsumer(pauseRegistry);
    }

    @Test
    void shouldCoordinateCancellationAcrossStoreTrackerAndRegistryAndAcknowledge() {
        Long workflowId = 20L;
        WorkflowCancelledEvent event = WorkflowCancelledEvent.builder()
                .workflowId(workflowId)
                .workflowName("Pipeline X")
                .build();

        cancellationConsumer.consume(event, acknowledgment);

        verify(coordinationService).markCancelled(workflowId);
        assertThat(cancellationRegistry.isCancelled(workflowId)).isTrue();
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldCoordinatePauseAcrossStoreTrackerAndRegistryAndAcknowledge() {
        Long workflowId = 21L;
        WorkflowPausedEvent event = WorkflowPausedEvent.builder()
                .workflowId(workflowId)
                .workflowName("Pipeline Y")
                .build();

        pauseConsumer.consume(event, acknowledgment);

        verify(coordinationService).markPaused(workflowId, null);
        assertThat(pauseRegistry.isPaused(workflowId)).isTrue();
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldClearPauseRegistryOnResumeAndAcknowledge() {
        Long workflowId = 22L;
        pauseRegistry.pause(workflowId);
        assertThat(pauseRegistry.isPaused(workflowId)).isTrue();

        WorkflowResumedEvent event = WorkflowResumedEvent.builder()
                .workflowId(workflowId)
                .workflowName("Pipeline Z")
                .build();

        resumeConsumer.consumeWorkflowResumed(event, acknowledgment);

        assertThat(pauseRegistry.isPaused(workflowId)).isFalse();
        verify(acknowledgment).acknowledge();
    }
}
