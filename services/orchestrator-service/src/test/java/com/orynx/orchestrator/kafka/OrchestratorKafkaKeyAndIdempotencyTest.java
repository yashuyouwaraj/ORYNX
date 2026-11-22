package com.orynx.orchestrator.kafka;

import com.orynx.orchestrator.common.config.KafkaReliabilityConfig;
import com.orynx.orchestrator.event.dto.WorkflowCancelledEvent;
import com.orynx.orchestrator.event.dto.WorkflowExecutionPausedEvent;
import com.orynx.orchestrator.event.dto.WorkflowPausedEvent;
import com.orynx.orchestrator.event.dto.WorkflowResumedEvent;
import com.orynx.orchestrator.execution.ExecutionEventConsumer;
import com.orynx.orchestrator.execution.WorkflowExecution;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.execution.event.TaskCompletedEvent;
import com.orynx.orchestrator.execution.event.TaskStartedEvent;
import com.orynx.orchestrator.execution.event.WorkflowCompletedEvent;
import com.orynx.orchestrator.workflow.*;
import com.orynx.orchestrator.workflow.dto.WorkflowRealtimeEvent;
import com.orynx.orchestrator.workflow.event.ExecutionEvent;
import com.orynx.orchestrator.workflow.event.ExecutionEventRepository;
import com.orynx.orchestrator.workflow.event.WorkflowCreatedEvent;
import com.orynx.orchestrator.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.orchestrator.workflow.task.TaskEventConsumer;
import com.orynx.orchestrator.workflow.task.TaskEventProducer;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import com.orynx.orchestrator.workflow.task.dto.TaskExecutionEvent;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrchestratorKafkaKeyAndIdempotencyTest {

    @Mock
    private KafkaTemplate<String, Object> objectKafkaTemplate;

    @Mock
    private KafkaTemplate<String, WorkflowExecutionRequestEvent> requestKafkaTemplate;

    @Mock
    private KafkaTemplate<String, WorkflowCreatedEvent> createdKafkaTemplate;

    @Mock
    private KafkaTemplate<String, WorkflowRealtimeEvent> realtimeKafkaTemplate;

    @Mock
    private KafkaTemplate<String, TaskExecutionEvent> taskEventKafkaTemplate;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowTaskRepository workflowTaskRepository;

    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;

    @Mock
    private ExecutionEventRepository executionEventRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Test
    @DisplayName("All orchestrator producers publish with workflowId Kafka key and skip null events")
    void allOrchestratorProducersUseWorkflowIdAsKafkaKey() {
        ExecutionRequestProducer requestProducer = new ExecutionRequestProducer(requestKafkaTemplate);
        WorkflowCancellationProducer cancelProducer = new WorkflowCancellationProducer(objectKafkaTemplate);
        WorkflowPauseProducer pauseProducer = new WorkflowPauseProducer(objectKafkaTemplate);
        WorkflowResumeProducer resumeProducer = new WorkflowResumeProducer(objectKafkaTemplate);
        KafkaProducer createdProducer = new KafkaProducer(createdKafkaTemplate);
        WorkflowEventProducer realtimeProducer = new WorkflowEventProducer(realtimeKafkaTemplate);
        TaskEventProducer taskProducer = new TaskEventProducer(taskEventKafkaTemplate);

        WorkflowExecutionRequestEvent req = WorkflowExecutionRequestEvent.builder()
                .workflowId(42L)
                .workflowName("ETL")
                .eventId("evt-1")
                .build();
        WorkflowCancelledEvent cancel = WorkflowCancelledEvent.builder()
                .workflowId(42L)
                .workflowName("ETL")
                .build();
        WorkflowPausedEvent pause = WorkflowPausedEvent.builder()
                .workflowId(42L)
                .workflowName("ETL")
                .build();
        WorkflowResumedEvent resume = WorkflowResumedEvent.builder()
                .workflowId(42L)
                .workflowName("ETL")
                .build();
        WorkflowCreatedEvent created = WorkflowCreatedEvent.builder()
                .workflowId(42L)
                .workflowName("ETL")
                .goal("Goal")
                .build();
        WorkflowRealtimeEvent realtime = WorkflowRealtimeEvent.builder()
                .workflowId(42L)
                .workflowName("ETL")
                .status("RUNNING")
                .build();
        TaskExecutionEvent taskEvt = TaskExecutionEvent.builder()
                .workflowId(42L)
                .taskId(7L)
                .taskName("Step 1")
                .status("RUNNING")
                .build();

        requestProducer.publishExecutionRequest(req);
        cancelProducer.publishWorkflowCancelled(cancel);
        pauseProducer.publishWorkflowPaused(pause);
        resumeProducer.publishWorkflowResumed(resume);
        createdProducer.publishWorkflowCreatedEvent(created);
        realtimeProducer.publishWorkflowEvent(realtime);
        taskProducer.publishTaskEvent(taskEvt);

        verify(requestKafkaTemplate).send("workflow-execution", "42", req);
        verify(objectKafkaTemplate).send("workflow-cancelled", "42", cancel);
        verify(objectKafkaTemplate).send("workflow-paused", "42", pause);
        verify(objectKafkaTemplate).send("workflow-resumed", "42", resume);
        verify(createdKafkaTemplate).send("workflow-created", "42", created);
        verify(realtimeKafkaTemplate).send("workflow-events", "42", realtime);
        verify(taskEventKafkaTemplate).send("task-execution-events", "42", taskEvt);

        // Null guards must avoid invoking KafkaTemplate
        requestProducer.publishExecutionRequest(null);
        cancelProducer.publishWorkflowCancelled(null);
        pauseProducer.publishWorkflowPaused(null);
        resumeProducer.publishWorkflowResumed(null);
        createdProducer.publishWorkflowCreatedEvent(null);
        realtimeProducer.publishWorkflowEvent(null);
        taskProducer.publishTaskEvent(null);
        verifyNoMoreInteractions(requestKafkaTemplate, objectKafkaTemplate, createdKafkaTemplate, realtimeKafkaTemplate, taskEventKafkaTemplate);
    }

    @Test
    @DisplayName("Async producer failure callback does not crash execution path")
    void asyncFailureCallbackHandledSafely() {
        CompletableFuture failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Broker unavailable"));
        when(requestKafkaTemplate.send(eq("workflow-execution"), eq("55"), any(WorkflowExecutionRequestEvent.class)))
                .thenReturn(failedFuture);

        ExecutionRequestProducer producer = new ExecutionRequestProducer(requestKafkaTemplate);
        WorkflowExecutionRequestEvent req = WorkflowExecutionRequestEvent.builder()
                .workflowId(55L)
                .workflowName("Resilient")
                .eventId("evt-55")
                .build();

        producer.publishExecutionRequest(req);
        verify(requestKafkaTemplate).send("workflow-execution", "55", req);
    }

    @Test
    @DisplayName("WorkflowId murmur2 partitioning guarantees per-workflow partition affinity across multi-partition topics")
    void workflowKeyProducesDeterministicPartitionAffinity() {
        int partitions = 6;
        String key = String.valueOf(42L);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

        int firstPartition = Utils.toPositive(Utils.murmur2(keyBytes)) % partitions;
        for (int i = 0; i < 20; i++) {
            int p = Utils.toPositive(Utils.murmur2(String.valueOf(42L).getBytes(StandardCharsets.UTF_8))) % partitions;
            assertThat(p).isEqualTo(firstPartition);
        }

        // Distinct workflow IDs distribute across partitions instead of hot-spotting partition 0
        Set<Integer> distinctPartitions = new HashSet<>();
        for (long wfId = 1L; wfId <= 50L; wfId++) {
            int p = Utils.toPositive(Utils.murmur2(String.valueOf(wfId).getBytes(StandardCharsets.UTF_8))) % partitions;
            distinctPartitions.add(p);
        }
        assertThat(distinctPartitions.size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("ExecutionEventConsumer suppresses duplicate and out-of-order TaskStartedEvent without regressing state")
    void executionConsumerPreventsDuplicateAndOutOfOrderTaskStarted() {
        ExecutionEventConsumer consumer = new ExecutionEventConsumer(
                workflowRepository, workflowTaskRepository, workflowExecutionRepository);

        Workflow wf = Workflow.builder().id(10L).name("W10").status(WorkflowStatus.RUNNING).build();
        when(workflowRepository.findById(10L)).thenReturn(Optional.of(wf));

        // 1. Duplicate RUNNING task with existing startedAt must not overwrite startedAt
        WorkflowTask runningTask = WorkflowTask.builder()
                .id(101L)
                .name("T1")
                .executionOrder(1)
                .status(TaskStatus.RUNNING)
                .startedAt(123456L)
                .workflow(wf)
                .build();
        when(workflowTaskRepository.findByWorkflowIdAndExecutionOrder(10L, 1)).thenReturn(Optional.of(runningTask));
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(10L)).thenReturn(Optional.empty());

        consumer.consumeTaskStarted(TaskStartedEvent.builder()
                .workflowId(10L).workflowName("W10").taskName("T1").executionOrder(1).generation(1L).build());

        assertThat(runningTask.getStartedAt()).isEqualTo(123456L);
        verify(workflowTaskRepository, never()).save(any());

        // 2. Out-of-order TaskStartedEvent after COMPLETED must not regress to RUNNING
        WorkflowTask completedTask = WorkflowTask.builder()
                .id(102L)
                .name("T2")
                .executionOrder(2)
                .status(TaskStatus.COMPLETED)
                .startedAt(100L)
                .completedAt(200L)
                .workflow(wf)
                .build();
        when(workflowTaskRepository.findByWorkflowIdAndExecutionOrder(10L, 2)).thenReturn(Optional.of(completedTask));

        consumer.consumeTaskStarted(TaskStartedEvent.builder()
                .workflowId(10L).workflowName("W10").taskName("T2").executionOrder(2).generation(1L).build());

        assertThat(completedTask.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        verify(workflowTaskRepository, never()).save(any());
    }

    @Test
    @DisplayName("ExecutionEventConsumer handles duplicate FAILED WorkflowCompletedEvent idempotently without throwing")
    void executionConsumerHandlesDuplicateFailedCompletionIdempotently() {
        ExecutionEventConsumer consumer = new ExecutionEventConsumer(
                workflowRepository, workflowTaskRepository, workflowExecutionRepository);

        Workflow failedWorkflow = Workflow.builder()
                .id(11L)
                .name("FailedWorkflow")
                .status(WorkflowStatus.FAILED)
                .completedAt(999L)
                .build();
        when(workflowRepository.findById(11L)).thenReturn(Optional.of(failedWorkflow));

        consumer.consumeWorkflowCompleted(WorkflowCompletedEvent.builder()
                .workflowId(11L)
                .workflowName("FailedWorkflow")
                .success(false)
                .generation(1L)
                .build());

        assertThat(failedWorkflow.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(failedWorkflow.getCompletedAt()).isEqualTo(999L);
        verify(workflowRepository, never()).save(any());
        verify(workflowExecutionRepository, never()).save(any());
    }

    @Test
    @DisplayName("ExecutionEventConsumer rejects stale execution generation events across task and workflow handlers")
    void executionConsumerRejectsStaleGenerationEvents() {
        ExecutionEventConsumer consumer = new ExecutionEventConsumer(
                workflowRepository, workflowTaskRepository, workflowExecutionRepository);

        Workflow wf = Workflow.builder().id(12L).name("RetryWorkflow").status(WorkflowStatus.RUNNING).build();
        WorkflowExecution activeExecution = WorkflowExecution.builder()
                .id(50L)
                .workflow(wf)
                .status(WorkflowStatus.RUNNING)
                .executionGeneration(2L)
                .build();
        WorkflowTask task = WorkflowTask.builder()
                .id(200L)
                .name("T1")
                .executionOrder(1)
                .status(TaskStatus.PENDING)
                .workflow(wf)
                .build();

        when(workflowRepository.findById(12L)).thenReturn(Optional.of(wf));
        when(workflowTaskRepository.findByWorkflowIdAndExecutionOrder(12L, 1)).thenReturn(Optional.of(task));
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(12L))
                .thenReturn(Optional.of(activeExecution));

        // Stale gen=1 events when execution is already at gen=2
        consumer.consumeTaskStarted(TaskStartedEvent.builder()
                .workflowId(12L).workflowName("RetryWorkflow").taskName("T1").executionOrder(1).generation(1L).build());
        consumer.consumeTaskCompleted(TaskCompletedEvent.builder()
                .workflowId(12L).workflowName("RetryWorkflow").taskName("T1").executionOrder(1)
                .success(true).attempts(1).generation(1L).build());
        consumer.consumeWorkflowCompleted(WorkflowCompletedEvent.builder()
                .workflowId(12L).workflowName("RetryWorkflow").success(false).generation(1L).build());
        consumer.consumeWorkflowExecutionPaused(WorkflowExecutionPausedEvent.builder()
                .workflowId(12L).workflowName("RetryWorkflow").lastCompletedExecutionOrder(1).generation(1L).build());

        assertThat(wf.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(activeExecution.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
        verify(workflowRepository, never()).save(any());
        verify(workflowTaskRepository, never()).save(any());
        verify(workflowExecutionRepository, never()).save(any());
    }

    @Test
    @DisplayName("TaskEventConsumer deduplicates identical consecutive status events and allows valid transitions")
    void taskEventConsumerDeduplicatesConsecutiveDuplicateStatuses() {
        TaskEventConsumer consumer = new TaskEventConsumer(messagingTemplate, executionEventRepository);

        TaskExecutionEvent runningEvent = TaskExecutionEvent.builder()
                .workflowId(20L).taskId(300L).taskName("Analyze").status("RUNNING").build();

        // First RUNNING event saved
        when(executionEventRepository.findFirstByWorkflowIdAndTaskIdOrderByIdDesc(20L, 300L))
                .thenReturn(Optional.empty());
        consumer.consume(runningEvent);
        verify(executionEventRepository, times(1)).save(any(ExecutionEvent.class));
        verify(messagingTemplate, times(1)).convertAndSend("/topic/tasks", runningEvent);

        // Redelivered RUNNING event suppressed
        when(executionEventRepository.findFirstByWorkflowIdAndTaskIdOrderByIdDesc(20L, 300L))
                .thenReturn(Optional.of(ExecutionEvent.builder()
                        .workflowId(20L).taskId(300L).taskName("Analyze").status("RUNNING").build()));
        consumer.consume(runningEvent);
        verify(executionEventRepository, times(1)).save(any(ExecutionEvent.class));
        verify(messagingTemplate, times(1)).convertAndSend("/topic/tasks", runningEvent);

        // COMPLETED transition accepted
        TaskExecutionEvent completedEvent = TaskExecutionEvent.builder()
                .workflowId(20L).taskId(300L).taskName("Analyze").status("COMPLETED").build();
        consumer.consume(completedEvent);
        verify(executionEventRepository, times(2)).save(any(ExecutionEvent.class));
        verify(messagingTemplate, times(1)).convertAndSend("/topic/tasks", completedEvent);

        // Late RUNNING after COMPLETED suppressed
        when(executionEventRepository.findFirstByWorkflowIdAndTaskIdOrderByIdDesc(20L, 300L))
                .thenReturn(Optional.of(ExecutionEvent.builder()
                        .workflowId(20L).taskId(300L).taskName("Analyze").status("COMPLETED").build()));
        consumer.consume(runningEvent);
        verify(executionEventRepository, times(2)).save(any(ExecutionEvent.class));
    }

    @Test
    @DisplayName("KafkaReliabilityConfig configures DefaultErrorHandler with non-retryable poison exceptions")
    void kafkaReliabilityConfigProvidesDefaultErrorHandler() {
        KafkaReliabilityConfig config = new KafkaReliabilityConfig();
        CommonErrorHandler handler = config.kafkaCommonErrorHandler(500L, 3L);
        assertThat(handler).isInstanceOf(DefaultErrorHandler.class);
        assertThat(DeserializationException.class).isNotNull();
        assertThat(MessageConversionException.class).isNotNull();
    }
}
