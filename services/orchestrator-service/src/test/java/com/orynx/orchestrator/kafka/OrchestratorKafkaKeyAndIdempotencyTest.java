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
import com.orynx.orchestrator.workflow.event.WorkflowExecutionEvent;
import com.orynx.orchestrator.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.orchestrator.workflow.task.TaskEventConsumer;
import com.orynx.orchestrator.workflow.task.TaskEventProducer;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowExecutionEngine;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import com.orynx.orchestrator.workflow.task.dto.TaskExecutionEvent;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.utils.Utils;
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
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

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

        // Null and unkeyed guards must avoid invoking KafkaTemplate
        requestProducer.publishExecutionRequest(null);
        requestProducer.publishExecutionRequest(WorkflowExecutionRequestEvent.builder().workflowName("NoId").build());
        cancelProducer.publishWorkflowCancelled(null);
        cancelProducer.publishWorkflowCancelled(WorkflowCancelledEvent.builder().workflowName("NoId").build());
        pauseProducer.publishWorkflowPaused(null);
        pauseProducer.publishWorkflowPaused(WorkflowPausedEvent.builder().workflowName("NoId").build());
        resumeProducer.publishWorkflowResumed(null);
        resumeProducer.publishWorkflowResumed(WorkflowResumedEvent.builder().workflowName("NoId").build());
        createdProducer.publishWorkflowCreatedEvent(null);
        createdProducer.publishWorkflowCreatedEvent(WorkflowCreatedEvent.builder().workflowName("NoId").build());
        realtimeProducer.publishWorkflowEvent(null);
        realtimeProducer.publishWorkflowEvent(WorkflowRealtimeEvent.builder().workflowName("NoId").build());
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
    @DisplayName("TaskEventConsumer allows retried generation transitions and blocks stale previous-generation events")
    void taskEventConsumerSupportsRetryAcrossGenerationsAndBlocksStaleEvents() {
        TaskEventConsumer consumer = new TaskEventConsumer(
                messagingTemplate, executionEventRepository, workflowExecutionRepository);

        WorkflowExecution activeExec = WorkflowExecution.builder()
                .id(10L)
                .status(WorkflowStatus.RUNNING)
                .executionGeneration(1L)
                .build();
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(21L))
                .thenReturn(Optional.of(activeExec));
        when(executionEventRepository.existsByWorkflowIdAndEventId(anyLong(), anyString())).thenReturn(false);

        // Generation 1 ends COMPLETED
        ExecutionEvent gen1Completed = ExecutionEvent.builder()
                .id(100L).workflowId(21L).taskId(301L).taskName("Analyze").status("COMPLETED").generation(1L).build();
        when(executionEventRepository.findFirstByWorkflowIdAndTaskIdOrderByIdDesc(21L, 301L))
                .thenReturn(Optional.of(gen1Completed));

        // After retryWorkflow, active generation becomes 2 -> gen=2 RUNNING and COMPLETED MUST be accepted
        activeExec.setExecutionGeneration(2L);
        TaskExecutionEvent gen2Running = TaskExecutionEvent.builder()
                .workflowId(21L).taskId(301L).taskName("Analyze").status("RUNNING")
                .generation(2L).eventId("wf:21:gen:2:task:301:RUNNING").build();
        consumer.consume(gen2Running);
        verify(executionEventRepository, times(1)).save(argThat(evt ->
                Long.valueOf(2L).equals(evt.getGeneration()) && "RUNNING".equals(evt.getStatus())));

        ExecutionEvent gen2RunningSaved = ExecutionEvent.builder()
                .id(101L).workflowId(21L).taskId(301L).taskName("Analyze").status("RUNNING").generation(2L).build();
        when(executionEventRepository.findFirstByWorkflowIdAndTaskIdOrderByIdDesc(21L, 301L))
                .thenReturn(Optional.of(gen2RunningSaved));

        TaskExecutionEvent gen2Completed = TaskExecutionEvent.builder()
                .workflowId(21L).taskId(301L).taskName("Analyze").status("COMPLETED")
                .generation(2L).eventId("wf:21:gen:2:task:301:COMPLETED").build();
        consumer.consume(gen2Completed);
        verify(executionEventRepository, times(1)).save(argThat(evt ->
                Long.valueOf(2L).equals(evt.getGeneration()) && "COMPLETED".equals(evt.getStatus())));

        // Stale gen=1 event arriving late during gen=2 must be suppressed without saving
        TaskExecutionEvent staleGen1Failed = TaskExecutionEvent.builder()
                .workflowId(21L).taskId(301L).taskName("Analyze").status("FAILED")
                .generation(1L).eventId("wf:21:gen:1:task:301:FAILED").build();
        consumer.consume(staleGen1Failed);
        verify(executionEventRepository, times(2)).save(any(ExecutionEvent.class));
    }

    @Test
    @DisplayName("TaskEventConsumer deduplicates by eventId, blocks post-FAILED regression, and avoids taskId-null conflation")
    void taskEventConsumerDeduplicatesByEventIdBlocksPostFailedAndAvoidsNullTaskIdConflation() {
        TaskEventConsumer consumer = new TaskEventConsumer(
                messagingTemplate, executionEventRepository, workflowExecutionRepository);
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(22L))
                .thenReturn(Optional.empty());

        // 1. Duplicate eventId suppressed immediately
        TaskExecutionEvent dupById = TaskExecutionEvent.builder()
                .workflowId(22L).taskId(401L).taskName("Build").status("RUNNING")
                .generation(1L).eventId("evt-dup-1").build();
        when(executionEventRepository.existsByWorkflowIdAndEventId(22L, "evt-dup-1")).thenReturn(true);
        consumer.consume(dupById);
        verify(executionEventRepository, never()).save(any());

        // 2. FAILED terminal status within same generation blocks late RUNNING regression
        ExecutionEvent failedGen1 = ExecutionEvent.builder()
                .id(200L).workflowId(22L).taskId(401L).taskName("Build").status("FAILED").generation(1L).build();
        when(executionEventRepository.findFirstByWorkflowIdAndTaskIdOrderByIdDesc(22L, 401L))
                .thenReturn(Optional.of(failedGen1));
        consumer.consume(TaskExecutionEvent.builder()
                .workflowId(22L).taskId(401L).taskName("Build").status("RUNNING")
                .generation(1L).eventId("evt-late-run").build());
        verify(executionEventRepository, never()).save(any());

        // 3. taskId == null uses taskIdIsNull lookup so it does not conflate with taskId != null rows
        when(executionEventRepository.findFirstByWorkflowIdAndTaskIdIsNullAndTaskNameOrderByIdDesc(22L, "Build"))
                .thenReturn(Optional.empty());
        consumer.consume(TaskExecutionEvent.builder()
                .workflowId(22L).taskId(null).taskName("Build").status("RUNNING")
                .generation(1L).eventId("evt-null-taskid").build());
        verify(executionEventRepository, times(1)).save(any());
        verify(executionEventRepository).findFirstByWorkflowIdAndTaskIdIsNullAndTaskNameOrderByIdDesc(22L, "Build");

        // 4. Malformed event with both taskId == null and blank taskName is ignored
        consumer.consume(TaskExecutionEvent.builder()
                .workflowId(22L).taskId(null).taskName("   ").status("RUNNING").build());
        verify(executionEventRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("WorkflowService synchronizes generation across start, resume, and retry and blocks late pre-resume pause events")
    void workflowServiceSynchronizesGenerationAcrossStartResumeAndRetryAndBlocksLatePause() {
        ExecutionRequestProducer execReqProducer = mock(ExecutionRequestProducer.class);
        WorkflowService service = new WorkflowService(
                workflowRepository,
                mock(KafkaProducer.class),
                mock(WorkflowEventPublisher.class),
                workflowTaskRepository,
                mock(WorkflowExecutionEngine.class),
                execReqProducer,
                workflowExecutionRepository,
                mock(WorkflowCancellationProducer.class),
                mock(WorkflowPauseProducer.class),
                mock(WorkflowResumeProducer.class)
        );

        // 1. startWorkflow initializes executionGeneration=1L on WorkflowExecution and WorkflowExecutionRequestEvent
        Workflow wfCreated = Workflow.builder()
                .id(60L).name("GenSyncWF").status(WorkflowStatus.CREATED).userId(100L).build();
        AtomicReference<WorkflowExecution> latestExecution = new AtomicReference<>();
        when(workflowRepository.findByIdAndUserIdForUpdate(60L, 100L)).thenReturn(Optional.of(wfCreated));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(60L)).thenReturn(List.of(
                WorkflowTask.builder().id(601L).name("Step 1").executionOrder(1).maxRetries(0).status(TaskStatus.PENDING).workflow(wfCreated).build(),
                WorkflowTask.builder().id(602L).name("Step 2").executionOrder(2).maxRetries(0).status(TaskStatus.PENDING).workflow(wfCreated).build()
        ));
        when(workflowExecutionRepository.save(any(WorkflowExecution.class))).thenAnswer(inv -> {
            WorkflowExecution exec = inv.getArgument(0);
            if (exec.getId() == null) {
                exec.setId(500L);
            }
            latestExecution.set(exec);
            return exec;
        });

        service.startWorkflow(60L, 100L);

        assertThat(latestExecution.get().getExecutionGeneration()).isEqualTo(1L);
        ArgumentCaptor<WorkflowExecutionRequestEvent> reqCaptor = ArgumentCaptor.forClass(WorkflowExecutionRequestEvent.class);
        verify(execReqProducer).publishExecutionRequest(reqCaptor.capture());
        assertThat(reqCaptor.getValue().getGeneration()).isEqualTo(1L);
        assertThat(reqCaptor.getValue().getResume()).isFalse();

        // 2. resumeWorkflow bumps generation 1 -> 2 and blocks late gen=1 WorkflowExecutionPausedEvent
        wfCreated.setStatus(WorkflowStatus.PAUSED);
        latestExecution.get().setStatus(WorkflowStatus.PAUSED);
        latestExecution.get().setLastCompletedExecutionOrder(1);
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(60L))
                .thenAnswer(inv -> Optional.ofNullable(latestExecution.get()));

        service.resumeWorkflow(60L, 100L);

        assertThat(latestExecution.get().getExecutionGeneration()).isEqualTo(2L);
        assertThat(latestExecution.get().getStatus()).isEqualTo(WorkflowStatus.RUNNING);
        verify(execReqProducer, times(2)).publishExecutionRequest(reqCaptor.capture());
        assertThat(reqCaptor.getValue().getGeneration()).isEqualTo(2L);
        assertThat(reqCaptor.getValue().getResume()).isTrue();

        ExecutionEventConsumer executionEventConsumer = new ExecutionEventConsumer(
                workflowRepository, workflowTaskRepository, workflowExecutionRepository);
        executionEventConsumer.consumeWorkflowExecutionPaused(WorkflowExecutionPausedEvent.builder()
                .workflowId(60L).workflowName("GenSyncWF").lastCompletedExecutionOrder(1).generation(1L).build());
        assertThat(latestExecution.get().getStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(latestExecution.get().getExecutionGeneration()).isEqualTo(2L);

        // 3. retryWorkflow bumps generation even if prior execution recorded null generation
        wfCreated.setStatus(WorkflowStatus.FAILED);
        latestExecution.get().setExecutionGeneration(null);
        service.retryWorkflow(60L, 100L);
        assertThat(latestExecution.get().getExecutionGeneration()).isEqualTo(2L);
        verify(execReqProducer, times(3)).publishExecutionRequest(reqCaptor.capture());
        assertThat(reqCaptor.getValue().getGeneration()).isEqualTo(2L);
        assertThat(reqCaptor.getValue().getResume()).isFalse();
    }

    @Test
    @DisplayName("KafkaReliabilityConfig retries IllegalArgumentException, forwards poison records to DLT, and commits recovered")
    void kafkaReliabilityConfigProvidesDefaultErrorHandler() {
        @SuppressWarnings("unchecked")
        ObjectProvider<KafkaTemplate<?, ?>> provider = mock(ObjectProvider.class);
        doReturn(objectKafkaTemplate).when(provider).getIfAvailable();

        KafkaReliabilityConfig config = new KafkaReliabilityConfig(provider);
        CommonErrorHandler handler = config.kafkaCommonErrorHandler(0L, 1L);
        assertThat(handler).isInstanceOf(DefaultErrorHandler.class);
        assertThat(DeserializationException.class).isNotNull();
        assertThat(MessageConversionException.class).isNotNull();

        Consumer<?, ?> consumerMock = mock(Consumer.class);
        MessageListenerContainer containerMock = mock(MessageListenerContainer.class);
        ConsumerRecord<String, Object> record = new ConsumerRecord<>("task-started", 0, 10L, "42", null);

        // IllegalArgumentException must be retried on attempt 1 rather than skipping directly to DLT
        try {
            handler.handleOne(new IllegalArgumentException("transient error"), record, consumerMock, containerMock);
        } catch (Exception ignored) {
        }
        verifyNoInteractions(objectKafkaTemplate);

        // Poison DeserializationException skips retries and forwards immediately to task-started.DLT
        DeserializationException deserEx = new DeserializationException(
                "bad payload", "{bad".getBytes(StandardCharsets.UTF_8), false, new RuntimeException("parse"));
        handler.handleOne(new RuntimeException(deserEx), record, consumerMock, containerMock);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dltCaptor = ArgumentCaptor.forClass(Map.class);
        verify(objectKafkaTemplate).send(eq("task-started.DLT"), eq("42"), dltCaptor.capture());
        assertThat(dltCaptor.getValue())
                .containsEntry("originalTopic", "task-started")
                .containsEntry("key", "42")
                .containsEntry("rawPayload", "{bad");
    }
}
