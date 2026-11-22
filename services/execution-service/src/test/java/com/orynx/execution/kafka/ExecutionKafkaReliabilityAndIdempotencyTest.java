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
import com.orynx.execution.event.dto.WorkflowCancelledEvent;
import com.orynx.execution.event.dto.WorkflowCompletedEvent;
import com.orynx.execution.event.dto.WorkflowExecutionPausedEvent;
import com.orynx.execution.event.dto.WorkflowPausedEvent;
import com.orynx.execution.event.dto.WorkflowResumedEvent;
import com.orynx.execution.execution.*;
import com.orynx.execution.execution.dto.TaskExecutionResult;
import com.orynx.execution.recovery.ExecutionRecoveryScanner;
import com.orynx.execution.worker.ExecutionWorkerProperties;
import com.orynx.execution.worker.WorkerQueueFullException;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.execution.workflow.event.dto.TaskExecutionRequest;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.serializer.DeserializationException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @DisplayName("WorkflowResumedEvent preserves PAUSED coordination status against recovery hijack until explicit resume=true claims lease")
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
        assertThat(paused.getStatus()).isEqualTo(ExecutionCoordinationStatus.PAUSED);
        assertThat(paused.getLeaseExpiresAt()).isNull();

        // Recovery scanner must NOT hijack a resumed-in-flight PAUSED record
        ExecutionCoordinationService.RecoveryResult recovery =
                coordinationService.reclaimForRecovery(78L, "scanner-node", 300L, 3);
        assertThat(recovery.outcome()).isEqualTo(ExecutionCoordinationService.RecoveryOutcome.INELIGIBLE);

        // Stale non-resume request must remain rejected while PAUSED
        ExecutionLeaseAcquisitionResult nonResumeRejected = coordinationService.tryAcquireLease(
                78L, "PauseResumeOrderB", "node-2", 300L, "{}", false);
        assertThat(nonResumeRejected.isAcquired()).isFalse();
        assertThat(nonResumeRejected.getStatus()).isEqualTo(ExecutionLeaseAcquisitionResult.Status.PAUSED);

        // Explicit resume=true execution request reclaims lease and bumps generation
        ExecutionLeaseAcquisitionResult acquired = coordinationService.tryAcquireLease(
                78L, "PauseResumeOrderB", "node-2", 300L, "{}", true, 2L);
        assertThat(acquired.isAcquired()).isTrue();
        assertThat(acquired.getGeneration()).isEqualTo(2L);
        assertThat(paused.getStatus()).isEqualTo(ExecutionCoordinationStatus.DISPATCHED);
    }

    @Test
    @DisplayName("Duplicate DISPATCHED execution request on same instance is rejected while lease is active")
    void duplicateSameInstanceRequestWhileDispatchedIsRejected() {
        ExecutionCoordinationService coordinationService = new ExecutionCoordinationService(coordinationRepository);
        WorkflowExecutionCoordination activeDispatched = WorkflowExecutionCoordination.builder()
                .workflowId(79L)
                .workflowName("SameNodeDup")
                .status(ExecutionCoordinationStatus.DISPATCHED)
                .instanceId("node-1")
                .generation(1L)
                .acquiredAt(Instant.now().minusSeconds(5))
                .leaseExpiresAt(Instant.now().plusSeconds(295))
                .build();
        when(coordinationRepository.findByWorkflowIdForUpdate(79L)).thenReturn(Optional.of(activeDispatched));

        ExecutionLeaseAcquisitionResult dup = coordinationService.tryAcquireLease(
                79L, "SameNodeDup", "node-1", 300L, "{}");
        assertThat(dup.isAcquired()).isFalse();
        assertThat(dup.getStatus()).isEqualTo(ExecutionLeaseAcquisitionResult.Status.DUPLICATE_ACTIVE);
        verify(coordinationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Queue saturation preserves coordination metadata, checkpoint, and generation continuity for retry and recovery")
    void queueSaturationReleasesLeaseWhilePreservingCoordinationStateForRetryAndRecovery() {
        ExecutionCoordinationService coordinationService = new ExecutionCoordinationService(coordinationRepository);
        AtomicReference<WorkflowExecutionCoordination> stored = new AtomicReference<>(
                WorkflowExecutionCoordination.builder()
                        .workflowId(88L)
                        .workflowName("QueueFullWorkflow")
                        .status(ExecutionCoordinationStatus.PAUSED)
                        .instanceId("node-1")
                        .generation(1L)
                        .lastCompletedExecutionOrder(2)
                        .acquiredAt(Instant.now().minusSeconds(60))
                        .payloadJson("{\"workflowId\":88,\"resume\":true}")
                        .build()
        );
        when(coordinationRepository.findByWorkflowIdForUpdate(88L)).thenAnswer(inv -> Optional.of(stored.get()));
        when(coordinationRepository.save(any(WorkflowExecutionCoordination.class))).thenAnswer(inv -> {
            WorkflowExecutionCoordination c = inv.getArgument(0);
            stored.set(c);
            return c;
        });

        ExecutionWorkerProperties workerProperties = new ExecutionWorkerProperties();
        when(dispatcher.getInstanceId()).thenReturn("node-2");
        WorkflowExecutionRequestEvent resumeReq = WorkflowExecutionRequestEvent.builder()
                .workflowId(88L)
                .workflowName("QueueFullWorkflow")
                .resume(true)
                .generation(2L)
                .eventId("evt-qf-88")
                .tasks(List.of(TaskExecutionRequest.builder().name("Task 3").executionOrder(3).maxRetries(0).build()))
                .build();

        doThrow(new WorkerQueueFullException("Queue saturated"))
                .when(executionService).executeWorkflow(eq(resumeReq), eq(2L));

        ExecutionRequestConsumer requestConsumer = new ExecutionRequestConsumer(
                executionService, coordinationService, dispatcher, workerProperties, new ObjectMapper());

        assertThatThrownBy(() -> requestConsumer.consume(resumeReq, acknowledgment))
                .isInstanceOf(WorkerQueueFullException.class);

        verify(acknowledgment, never()).acknowledge();
        verify(coordinationRepository, never()).delete(any());
        assertThat(stored.get().getStatus()).isEqualTo(ExecutionCoordinationStatus.DISPATCHED);
        assertThat(stored.get().getGeneration()).isEqualTo(1L);
        assertThat(stored.get().getLeaseExpiresAt()).isEqualTo(Instant.EPOCH);
        assertThat(stored.get().getLastCompletedExecutionOrder()).isEqualTo(2);
        assertThat(stored.get().getPayloadJson()).contains("\"workflowId\":88");

        // Subsequent retry re-acquires exact generation 2 and succeeds
        doNothing().when(executionService).executeWorkflow(eq(resumeReq), eq(2L));
        requestConsumer.consume(resumeReq, acknowledgment);
        assertThat(stored.get().getGeneration()).isEqualTo(2L);
        verify(acknowledgment).acknowledge();

        // Recovery scanner hitting WorkerQueueFullException also releases lease without losing row
        stored.get().setLeaseExpiresAt(Instant.now().minusSeconds(10));
        stored.get().setGeneration(2L);
        doThrow(new WorkerQueueFullException("Still saturated")).when(dispatcher).dispatch(any(), anyLong());
        ExecutionRecoveryScanner scanner = new ExecutionRecoveryScanner(
                coordinationService, dispatcher, mock(ExecutionEventProducer.class), workerProperties, new ObjectMapper());
        scanner.recoverExecution(stored.get());
        assertThat(stored.get().getLeaseExpiresAt()).isEqualTo(Instant.EPOCH);
        assertThat(stored.get().getGeneration()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Delayed WorkflowPausedEvent cannot overwrite CANCELLED/COMPLETED/FAILED coordination states or set pause flags")
    void cancellationAndPauseRacePreservesTerminalState() {
        ExecutionCoordinationService coordinationService = new ExecutionCoordinationService(coordinationRepository);
        WorkflowExecutionCoordination cancelled = WorkflowExecutionCoordination.builder()
                .workflowId(90L)
                .workflowName("CancelledRace")
                .status(ExecutionCoordinationStatus.CANCELLED)
                .generation(1L)
                .acquiredAt(Instant.now())
                .build();
        when(coordinationRepository.findByWorkflowIdForUpdate(90L)).thenReturn(Optional.of(cancelled));
        when(coordinationRepository.findById(90L)).thenReturn(Optional.of(cancelled));

        coordinationService.markPaused(90L, 2);
        assertThat(cancelled.getStatus()).isEqualTo(ExecutionCoordinationStatus.CANCELLED);

        WorkflowPauseRegistry pauseRegistry = new WorkflowPauseRegistry();
        ActiveExecutionTracker tracker = new ActiveExecutionTracker();
        WorkflowPauseConsumer pauseConsumer = new WorkflowPauseConsumer(pauseRegistry, tracker, coordinationService);
        pauseConsumer.consume(WorkflowPausedEvent.builder().workflowId(90L).workflowName("CancelledRace").build(), acknowledgment);

        assertThat(pauseRegistry.isPaused(90L)).isFalse();
        verify(acknowledgment).acknowledge();
        verify(coordinationRepository, never()).save(any());

        // FAILED must also remain terminal against late pause or cancel
        WorkflowExecutionCoordination failed = WorkflowExecutionCoordination.builder()
                .workflowId(91L)
                .status(ExecutionCoordinationStatus.FAILED)
                .generation(1L)
                .acquiredAt(Instant.now())
                .build();
        when(coordinationRepository.findByWorkflowIdForUpdate(91L)).thenReturn(Optional.of(failed));
        coordinationService.markPaused(91L, 1);
        coordinationService.markCancelled(91L);
        assertThat(failed.getStatus()).isEqualTo(ExecutionCoordinationStatus.FAILED);
    }

    @Test
    @DisplayName("ExecutionCoordinationService honors requestedGeneration on initial and reclaim lease acquisitions")
    void coordinationServiceSynchronizesWithRequestedGeneration() {
        ExecutionCoordinationService coordinationService = new ExecutionCoordinationService(coordinationRepository);
        when(coordinationRepository.findByWorkflowIdForUpdate(95L)).thenReturn(Optional.empty());
        when(coordinationRepository.save(any(WorkflowExecutionCoordination.class))).thenAnswer(inv -> inv.getArgument(0));

        ExecutionLeaseAcquisitionResult initial = coordinationService.tryAcquireLease(
                95L, "SyncedGen", "node-1", 300L, "{}", false, 3L);
        assertThat(initial.isAcquired()).isTrue();
        assertThat(initial.getGeneration()).isEqualTo(3L);

        WorkflowExecutionCoordination failed = WorkflowExecutionCoordination.builder()
                .workflowId(96L)
                .status(ExecutionCoordinationStatus.FAILED)
                .generation(1L)
                .acquiredAt(Instant.now())
                .build();
        when(coordinationRepository.findByWorkflowIdForUpdate(96L)).thenReturn(Optional.of(failed));
        ExecutionLeaseAcquisitionResult retried = coordinationService.tryAcquireLease(
                96L, "SyncedRetry", "node-2", 300L, "{}", false, 5L);
        assertThat(retried.isAcquired()).isTrue();
        assertThat(retried.getGeneration()).isEqualTo(5L);
    }

    @Test
    @DisplayName("Execution KafkaReliabilityConfig retries IllegalArgumentException, confirms DLT before commit, and fails closed on DLT failure")
    void executionKafkaReliabilityConfigBuildsDefaultErrorHandler() {
        @SuppressWarnings("unchecked")
        ObjectProvider<KafkaTemplate<?, ?>> provider = mock(ObjectProvider.class);
        doReturn(kafkaTemplate).when(provider).getIfAvailable();

        KafkaReliabilityConfig config = new KafkaReliabilityConfig(provider);
        CommonErrorHandler handler = config.kafkaCommonErrorHandler(0L, 1L);
        assertThat(handler).isInstanceOf(DefaultErrorHandler.class);

        Consumer<?, ?> consumerMock = mock(Consumer.class);
        MessageListenerContainer containerMock = mock(MessageListenerContainer.class);
        ConsumerRecord<String, Object> record = new ConsumerRecord<>("workflow-execution", 0, 4L, "95", null);

        try {
            handler.handleOne(new IllegalArgumentException("retryable app error"), record, consumerMock, containerMock);
        } catch (Exception ignored) {
        }
        verifyNoInteractions(kafkaTemplate);

        when(kafkaTemplate.send(eq("workflow-execution.DLT"), eq("95"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        DeserializationException deserEx = new DeserializationException(
                "bad", "{broken".getBytes(StandardCharsets.UTF_8), false, new RuntimeException("parse"));
        boolean recovered = handler.handleOne(new RuntimeException(deserEx), record, consumerMock, containerMock);
        assertThat(recovered).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dltCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaTemplate).send(eq("workflow-execution.DLT"), eq("95"), dltCaptor.capture());
        assertThat(dltCaptor.getValue())
                .containsEntry("originalTopic", "workflow-execution")
                .containsEntry("key", "95")
                .containsEntry("rawPayload", "{broken");

        when(kafkaTemplate.send(eq("workflow-execution.DLT"), eq("95"), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("DLT broker unavailable")));
        assertThat(handler.handleOne(new RuntimeException(deserEx), record, consumerMock, containerMock)).isFalse();
        assertThatThrownBy(() -> handler.handleRemaining(
                new RuntimeException(deserEx), List.of(record), consumerMock, containerMock))
                .isInstanceOf(Exception.class);
        verify(consumerMock, never()).commitSync(org.mockito.ArgumentMatchers.anyMap(), any());
        verify(consumerMock, never()).commitAsync(org.mockito.ArgumentMatchers.anyMap(), any());
    }
}
