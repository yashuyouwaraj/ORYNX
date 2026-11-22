package com.orynx.execution.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.execution.config.KafkaReliabilityConfig;
import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.coordination.ExecutionCoordinationStatus;
import com.orynx.execution.coordination.ExecutionLeaseAcquisitionResult;
import com.orynx.execution.coordination.WorkflowExecutionCoordination;
import com.orynx.execution.coordination.WorkflowExecutionCoordinationRepository;
import com.orynx.execution.event.dto.TaskCompletedEvent;
import com.orynx.execution.event.dto.TaskStartedEvent;
import com.orynx.execution.event.dto.WorkflowCompletedEvent;
import com.orynx.execution.event.dto.WorkflowExecutionPausedEvent;
import com.orynx.execution.event.dto.WorkflowResumedEvent;
import com.orynx.execution.execution.*;
import com.orynx.execution.execution.dto.TaskExecutionResult;
import com.orynx.execution.worker.ExecutionWorkerProperties;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.execution.workflow.event.dto.TaskExecutionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionKafkaReliabilityAndIdempotencyTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private WorkflowExecutionCoordinationRepository coordinationRepository;

    @Mock
    private ExecutionService executionService;

    @Mock
    private WorkflowExecutionDispatcher dispatcher;

    @Mock
    private Acknowledgment acknowledgment;

    @Test
    @DisplayName("ExecutionEventProducer uses workflowId as Kafka key across all emitted execution events")
    void executionEventProducerKeysAllEventsByWorkflowId() {
        ExecutionEventProducer producer = new ExecutionEventProducer(kafkaTemplate);

        TaskStartedEvent started = TaskStartedEvent.builder()
                .workflowId(91L).workflowName("WF").taskName("T1").executionOrder(1).generation(2L).build();
        TaskCompletedEvent completed = TaskCompletedEvent.builder()
                .workflowId(91L).workflowName("WF").taskName("T1").executionOrder(1).success(true).attempts(1).generation(2L).build();
        WorkflowCompletedEvent wfDone = WorkflowCompletedEvent.builder()
                .workflowId(91L).workflowName("WF").success(true).generation(2L).build();
        WorkflowExecutionPausedEvent wfPaused = WorkflowExecutionPausedEvent.builder()
                .workflowId(91L).workflowName("WF").lastCompletedExecutionOrder(1).generation(2L).build();

        producer.publishTaskStarted(started);
        producer.publishTaskCompleted(completed);
        producer.publishWorkflowCompleted(wfDone);
        producer.publishWorkflowExecutionPaused(wfPaused);

        verify(kafkaTemplate).send("task-started", "91", started);
        verify(kafkaTemplate).send("task-completed", "91", completed);
        verify(kafkaTemplate).send("workflow-completed", "91", wfDone);
        verify(kafkaTemplate).send("workflow-execution-paused", "91", wfPaused);

        producer.publishTaskStarted(null);
        producer.publishTaskCompleted(null);
        producer.publishWorkflowCompleted(null);
        producer.publishWorkflowExecutionPaused(null);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("WorkflowExecutionEngine populates fencing generation and deterministic eventId on every emitted event")
    void enginePopulatesGenerationAndEventId() {
        ExecutionEventProducer eventProducer = mock(ExecutionEventProducer.class);
        TaskExecutor taskExecutor = mock(TaskExecutor.class);
        when(taskExecutor.execute(any(TaskExecutionRequest.class)))
                .thenReturn(TaskExecutionResult.builder().taskName("Step 1").success(true).attempts(1).build());
        WorkflowExecutionEngine engine = new WorkflowExecutionEngine(
                taskExecutor,
                eventProducer,
                new WorkflowCancellationRegistry(),
                new WorkflowPauseRegistry(),
                null,
                null
        );

        WorkflowExecutionRequestEvent req = WorkflowExecutionRequestEvent.builder()
                .workflowId(300L)
                .workflowName("GenTest")
                .tasks(List.of(TaskExecutionRequest.builder().name("Step 1").executionOrder(1).maxRetries(1).build()))
                .build();

        engine.execute(req, 4L, "worker-node-4");

        ArgumentCaptor<TaskStartedEvent> startedCaptor = ArgumentCaptor.forClass(TaskStartedEvent.class);
        verify(eventProducer).publishTaskStarted(startedCaptor.capture());
        assertThat(startedCaptor.getValue().getGeneration()).isEqualTo(4L);
        assertThat(startedCaptor.getValue().getEventId()).isEqualTo("wf:300:gen:4:task-started:1");

        ArgumentCaptor<TaskCompletedEvent> completedCaptor = ArgumentCaptor.forClass(TaskCompletedEvent.class);
        verify(eventProducer).publishTaskCompleted(completedCaptor.capture());
        assertThat(completedCaptor.getValue().getGeneration()).isEqualTo(4L);
        assertThat(completedCaptor.getValue().getEventId()).isEqualTo("wf:300:gen:4:task-completed:1");

        ArgumentCaptor<WorkflowCompletedEvent> wfDoneCaptor = ArgumentCaptor.forClass(WorkflowCompletedEvent.class);
        verify(eventProducer).publishWorkflowCompleted(wfDoneCaptor.capture());
        assertThat(wfDoneCaptor.getValue().getGeneration()).isEqualTo(4L);
        assertThat(wfDoneCaptor.getValue().getEventId()).isEqualTo("wf:300:gen:4:workflow-completed");
    }

    @Test
    @DisplayName("Paused workflow rejects non-resume duplicate request, but reclaims lease with incremented generation when resume=true arrives first")
    void pauseThenResumeCoordinationWorksRegardlessOfCrossTopicArrivalOrder() {
        ExecutionCoordinationService coordinationService = new ExecutionCoordinationService(coordinationRepository);
        AtomicReference<WorkflowExecutionCoordination> stored = new AtomicReference<>(
                WorkflowExecutionCoordination.builder()
                        .workflowId(77L)
                        .workflowName("PauseResume")
                        .status(ExecutionCoordinationStatus.PAUSED)
                        .instanceId("node-1")
                        .generation(1L)
                        .lastCompletedExecutionOrder(2)
                        .acquiredAt(Instant.now().minusSeconds(60))
                        .build()
        );
        when(coordinationRepository.findByWorkflowIdForUpdate(77L)).thenAnswer(inv -> Optional.of(stored.get()));
        when(coordinationRepository.save(any(WorkflowExecutionCoordination.class))).thenAnswer(inv -> {
            WorkflowExecutionCoordination c = inv.getArgument(0);
            stored.set(c);
            return c;
        });

        // 1. Un-resumed duplicate execution request must be rejected while PAUSED
        ExecutionLeaseAcquisitionResult rejected = coordinationService.tryAcquireLease(
                77L, "PauseResume", "node-2", 300L, "{}");
        assertThat(rejected.isAcquired()).isFalse();
        assertThat(rejected.getStatus()).isEqualTo(ExecutionLeaseAcquisitionResult.Status.PAUSED);

        // 2. ExecutionRequestEvent(resume=true) arrives BEFORE WorkflowResumedEvent
        WorkflowPauseRegistry pauseRegistry = new WorkflowPauseRegistry();
        pauseRegistry.pause(77L);
        ActiveExecutionTracker tracker = new ActiveExecutionTracker();
        ExecutionWorkerProperties workerProperties = new ExecutionWorkerProperties();
        when(dispatcher.getInstanceId()).thenReturn("node-2");

        ExecutionRequestConsumer requestConsumer = new ExecutionRequestConsumer(
                executionService,
                coordinationService,
                dispatcher,
                workerProperties,
                new ObjectMapper(),
                pauseRegistry,
                tracker
        );

        WorkflowExecutionRequestEvent resumeReq = WorkflowExecutionRequestEvent.builder()
                .workflowId(77L)
                .workflowName("PauseResume")
                .resume(true)
                .eventId("evt-resume-77")
                .tasks(List.of(TaskExecutionRequest.builder().name("Task 3").executionOrder(3).maxRetries(2).build()))
                .build();

        requestConsumer.consume(resumeReq, acknowledgment);

        assertThat(pauseRegistry.isPaused(77L)).isFalse();
        assertThat(stored.get().getStatus()).isEqualTo(ExecutionCoordinationStatus.DISPATCHED);
        assertThat(stored.get().getGeneration()).isEqualTo(2L);
        assertThat(stored.get().getLastCompletedExecutionOrder()).isEqualTo(2);
        verify(executionService).executeWorkflow(resumeReq, 2L);
        verify(acknowledgment).acknowledge();

        // 3. Late WorkflowResumedEvent arrives after request already claimed lease -> safe idempotent no-op
        WorkflowResumeConsumer resumeConsumer = new WorkflowResumeConsumer(pauseRegistry, coordinationService, tracker);
        resumeConsumer.consumeWorkflowResumed(WorkflowResumedEvent.builder()
                .workflowId(77L).workflowName("PauseResume").eventId("evt-r-1").build(), acknowledgment);

        assertThat(stored.get().getStatus()).isEqualTo(ExecutionCoordinationStatus.DISPATCHED);
        assertThat(stored.get().getGeneration()).isEqualTo(2L);
        verify(acknowledgment, times(2)).acknowledge();
    }

    @Test
    @DisplayName("WorkflowResumedEvent arriving first unlocks PAUSED coordination record for subsequent execution request")
    void workflowResumedEventUnlocksCoordinationRecordWhenArrivingFirst() {
        ExecutionCoordinationService coordinationService = new ExecutionCoordinationService(coordinationRepository);
        WorkflowExecutionCoordination paused = WorkflowExecutionCoordination.builder()
                .workflowId(78L)
                .workflowName("PauseResumeOrderB")
                .status(ExecutionCoordinationStatus.PAUSED)
                .instanceId("node-1")
                .generation(1L)
                .lastCompletedExecutionOrder(1)
                .acquiredAt(Instant.now().minusSeconds(60))
                .build();
        when(coordinationRepository.findByWorkflowIdForUpdate(78L)).thenReturn(Optional.of(paused));
        when(coordinationRepository.save(any(WorkflowExecutionCoordination.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowPauseRegistry pauseRegistry = new WorkflowPauseRegistry();
        pauseRegistry.pause(78L);
        ActiveExecutionTracker tracker = new ActiveExecutionTracker();

        WorkflowResumeConsumer resumeConsumer = new WorkflowResumeConsumer(pauseRegistry, coordinationService, tracker);
        resumeConsumer.consumeWorkflowResumed(WorkflowResumedEvent.builder()
                .workflowId(78L).workflowName("PauseResumeOrderB").eventId("evt-r-2").build(), acknowledgment);

        assertThat(pauseRegistry.isPaused(78L)).isFalse();
        assertThat(paused.getStatus()).isEqualTo(ExecutionCoordinationStatus.DISPATCHED);
        assertThat(paused.getLeaseExpiresAt()).isEqualTo(Instant.EPOCH);

        ExecutionLeaseAcquisitionResult acquired = coordinationService.tryAcquireLease(
                78L, "PauseResumeOrderB", "node-2", 300L, "{}", true);
        assertThat(acquired.isAcquired()).isTrue();
        assertThat(acquired.getGeneration()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Execution KafkaReliabilityConfig builds bounded DefaultErrorHandler")
    void executionKafkaReliabilityConfigBuildsDefaultErrorHandler() {
        KafkaReliabilityConfig config = new KafkaReliabilityConfig();
        CommonErrorHandler handler = config.kafkaCommonErrorHandler(500L, 3L);
        assertThat(handler).isInstanceOf(DefaultErrorHandler.class);
    }
}
