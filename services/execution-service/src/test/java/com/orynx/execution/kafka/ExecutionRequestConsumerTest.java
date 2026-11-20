package com.orynx.execution.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.coordination.ExecutionLeaseAcquisitionResult;
import com.orynx.execution.coordination.WorkflowExecutionCoordination;
import com.orynx.execution.execution.ExecutionService;
import com.orynx.execution.execution.WorkflowExecutionDispatcher;
import com.orynx.execution.worker.ExecutionWorkerProperties;
import com.orynx.execution.worker.WorkerQueueFullException;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.execution.workflow.event.dto.TaskExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionRequestConsumerTest {

    @Mock
    private ExecutionService executionService;

    @Mock
    private ExecutionCoordinationService coordinationService;

    @Mock
    private WorkflowExecutionDispatcher dispatcher;

    @Mock
    private Acknowledgment acknowledgment;

    private ExecutionWorkerProperties properties;
    private ObjectMapper objectMapper;
    private ExecutionRequestConsumer consumer;

    @BeforeEach
    void setUp() {
        properties = new ExecutionWorkerProperties();
        properties.setLeaseDurationSeconds(300L);
        objectMapper = new ObjectMapper();
        lenient().when(dispatcher.getInstanceId()).thenReturn("test-instance-1");

        consumer = new ExecutionRequestConsumer(
                executionService,
                coordinationService,
                dispatcher,
                properties,
                objectMapper
        );
    }

    @Test
    void shouldConsumeValidRequestAcquireLeaseDispatchAndAcknowledgeOffset() {
        WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                .workflowId(10L)
                .workflowName("Process Data")
                .tasks(List.of(TaskExecutionRequest.builder().name("Task 1").executionOrder(1).build()))
                .build();

        WorkflowExecutionCoordination coordination = WorkflowExecutionCoordination.builder()
                .workflowId(10L)
                .generation(1L)
                .build();
        when(coordinationService.tryAcquireLease(eq(10L), eq("Process Data"), anyString(), anyLong(), any()))
                .thenReturn(ExecutionLeaseAcquisitionResult.acquired(coordination));

        consumer.consume(event, acknowledgment);

        verify(executionService).executeWorkflow(event, 1L);
        verify(acknowledgment).acknowledge();
        verify(coordinationService, never()).releaseLeaseOnDispatchFailure(any());
    }

    @Test
    void shouldDiscardDuplicateOrIneligibleRequestAndAcknowledgeOffsetWithoutDispatch() {
        WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                .workflowId(11L)
                .workflowName("Duplicate Job")
                .build();

        WorkflowExecutionCoordination coordination = WorkflowExecutionCoordination.builder()
                .workflowId(11L)
                .instanceId("other-node")
                .generation(1L)
                .build();
        when(coordinationService.tryAcquireLease(eq(11L), eq("Duplicate Job"), anyString(), anyLong(), any()))
                .thenReturn(ExecutionLeaseAcquisitionResult.duplicateActive(coordination));

        consumer.consume(event, acknowledgment);

        // Must NOT dispatch to worker pool
        verify(executionService, never()).executeWorkflow(any(), any());
        // Must acknowledge to remove duplicate message from Kafka partition
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldTriggerBackpressureWhenWorkerQueueSaturatedWithoutAcknowledgingOffset() {
        WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                .workflowId(12L)
                .workflowName("High Load Job")
                .build();

        WorkflowExecutionCoordination coordination = WorkflowExecutionCoordination.builder()
                .workflowId(12L)
                .generation(1L)
                .build();
        when(coordinationService.tryAcquireLease(eq(12L), eq("High Load Job"), anyString(), anyLong(), any()))
                .thenReturn(ExecutionLeaseAcquisitionResult.acquired(coordination));

        doThrow(new WorkerQueueFullException("Queue full"))
                .when(executionService).executeWorkflow(eq(event), anyLong());

        assertThatThrownBy(() -> consumer.consume(event, acknowledgment))
                .isInstanceOf(WorkerQueueFullException.class);

        // Offset must NOT be acknowledged so Kafka can redeliver / backpressure
        verify(acknowledgment, never()).acknowledge();
        // Lease must be released so subsequent retry can acquire
        verify(coordinationService).releaseLeaseOnDispatchFailure(12L);
    }

    @Test
    void shouldAcknowledgeAndDiscardMalformedNullPayload() {
        consumer.consume(null, acknowledgment);
        verify(acknowledgment).acknowledge();
        verify(executionService, never()).executeWorkflow(any(), any());

        WorkflowExecutionRequestEvent invalidEvent = new WorkflowExecutionRequestEvent();
        consumer.consume(invalidEvent, acknowledgment);
        verify(acknowledgment, times(2)).acknowledge();
    }
}
